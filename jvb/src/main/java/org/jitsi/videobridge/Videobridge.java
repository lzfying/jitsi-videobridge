/*
 * Copyright @ 2015 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import kotlin.*;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.stats.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.load_management.*;
import org.jitsi.videobridge.relay.*;
import org.jitsi.videobridge.shutdown.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.health.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.json.simple.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.jitsi.videobridge.colibri2.Colibri2UtilKt.createConferenceAlreadyExistsError;
import static org.jitsi.videobridge.colibri2.Colibri2UtilKt.createConferenceNotFoundError;
import static org.jitsi.xmpp.util.ErrorUtilKt.createError;

/**
 * Represents the Jitsi Videobridge which creates, lists and destroys
 * {@link Conference} instances.
 *
 * @author Lyubomir Marinov
 * @author Hristo Terezov
 * @author Boris Grozev
 * @author Brian Baldino
 */
@SuppressWarnings("JavadocReference")
public class Videobridge
{
    /**
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = new LoggerImpl(Videobridge.class.getName());

    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    public static final Random RANDOM = new Random();

    /**
     * The REST-like HTTP/JSON API of Jitsi Videobridge.
     */
    public static final String REST_API = "rest";

    /**
     * The (base) <tt>System</tt> and/or <tt>ConfigurationService</tt> property
     * of the REST-like HTTP/JSON API of Jitsi Videobridge.
     *
     * NOTE: Previously, the rest API name ("org.jitsi.videobridge.rest")
     * conflicted with other values in the new config, since we have
     * other properties scoped *under* "org.jitsi.videobridge.rest".   The
     * long term solution will be to port the command-line args to new config,
     * but for now we rename the property used to signal the rest API is
     * enabled so it doesn't conflict.
     */
    public static final String REST_API_PNAME = "org.jitsi.videobridge." + REST_API + "_api_temp";

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * local IDs.
     */
    private final Map<String, Conference> conferencesById = new HashMap<>();

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * meeting IDs.
     */
    private final Map<String, Conference> conferencesByMeetingId = new HashMap<>();

    private final JvbHealthChecker jvbHealthChecker = new JvbHealthChecker();

    /**
     * The clock to use, pluggable for testing purposes.
     *
     * Note that currently most code uses the system clock directly.
     */
    @NotNull
    private final Clock clock;

    /**
     * A class that holds some instance statistics.
     */
    private final Statistics statistics = new Statistics();

    /**
     * Thread that checks expiration for conferences, contents, channels and
     * execute expire procedure for any of them.
     */
    private final VideobridgeExpireThread videobridgeExpireThread;

    /**
     * The {@link JvbLoadManager} instance used for this bridge.
     */
    private final JvbLoadManager<PacketRateMeasurement> jvbLoadManager;

    /**
     * The task which manages the recurring load sampling and updating of
     * {@link Videobridge#jvbLoadManager}.
     */
    private final ScheduledFuture<?> loadSamplerTask;

    @NotNull private final Version version;
    @Nullable private final String releaseId;

    @NotNull private final ShutdownManager shutdownManager;

    private final EventEmitter<EventHandler> eventEmitter = new SyncEventEmitter<>();

    static
    {
        org.jitsi.rtp.util.BufferPool.Companion.setGetArray(ByteBufferPool::getBuffer);
        org.jitsi.rtp.util.BufferPool.Companion.setReturnArray(buffer -> {
            ByteBufferPool.returnBuffer(buffer);
            return Unit.INSTANCE;
        });
        org.jitsi.nlj.util.BufferPool.Companion.setGetBuffer(ByteBufferPool::getBuffer);
        org.jitsi.nlj.util.BufferPool.Companion.setReturnBuffer(buffer -> {
            ByteBufferPool.returnBuffer(buffer);
            return Unit.INSTANCE;
        });
    }

    /**
     * Initializes a new <tt>Videobridge</tt> instance.
     */
    public Videobridge(
        @Nullable XmppConnection xmppConnection,
        @NotNull ShutdownServiceImpl shutdownService,
        @NotNull Version version,
        @Nullable String releaseId,
        @NotNull Clock clock)
    {
        this.clock = clock;
        videobridgeExpireThread = new VideobridgeExpireThread(this);
        jvbLoadManager = new JvbLoadManager<>(
            PacketRateMeasurement.getLoadedThreshold(),
            PacketRateMeasurement.getRecoveryThreshold(),
            new LastNReducer(
                this::getConferences,
                JvbLastNKt.jvbLastNSingleton
            )
        );
        loadSamplerTask = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(
            new PacketRateLoadSampler(
                this,
                (loadMeasurement) -> {
                    // Update the load manager with the latest measurement
                    jvbLoadManager.loadUpdate(loadMeasurement);
                    // Update the stats with the latest stress level
                    getStatistics().stressLevel = jvbLoadManager.getCurrentStressLevel();
                    return Unit.INSTANCE;
                }
            ),
            0,
            10,
            TimeUnit.SECONDS
        );
        if (xmppConnection != null)
        {
            xmppConnection.setEventHandler(new XmppConnectionEventHandler());
        }
        this.version = version;
        this.releaseId = releaseId;
        this.shutdownManager = new ShutdownManager(shutdownService, logger);
        jvbHealthChecker.start();
    }

    @NotNull
    public JvbHealthChecker getJvbHealthChecker()
    {
        return jvbHealthChecker;
    }

    /**
     * Generate conference IDs until one is found that isn't in use and create a new {@link Conference}
     * object using that ID
     *
     * @param checkForMeetingIdCollision whether to throw an exception if a conference with the given meetingId exists.
     * With colibri1 we allow more than one conference with the same meetingId because there's no mechanism to
     * immediately expire a conference.
     */
    private @NotNull Conference doCreateConference(
            @Nullable EntityBareJid name,
            String meetingId,
            boolean isRtcStatsEnabled,
            boolean isCallStatsEnabled,
            boolean checkForMeetingIdCollision)
    {
        Conference conference = null;
        do
        {
            String id = generateConferenceID();

            synchronized (conferencesById)
            {
                if (checkForMeetingIdCollision && meetingId != null && conferencesByMeetingId.containsKey(meetingId))
                {
                    throw new IllegalStateException("Already have a meeting with meetingId " + meetingId);
                }

                if (!conferencesById.containsKey(id))
                {
                    conference = new Conference(this, id, name, meetingId, isRtcStatsEnabled, isCallStatsEnabled);
                    conferencesById.put(id, conference);
                    if (meetingId != null)
                    {
                        conferencesByMeetingId.put(meetingId, conference);
                    }
                }
            }
        }
        while (conference == null);

        return conference;
    }

    void localEndpointCreated()
    {
        statistics.currentLocalEndpoints.incrementAndGet();
    }

    void localEndpointExpired()
    {
        long remainingEndpoints = statistics.currentLocalEndpoints.decrementAndGet();
        if (remainingEndpoints < 0)
        {
            logger.warn("Invalid endpoint count " + remainingEndpoints + ". Disabling endpoint-count based shutdown!");
            return;
        }
        shutdownManager.maybeShutdown(remainingEndpoints);
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances.
     *
     * @param name world readable name of the conference to create.
     * @param gid the "global" id of the conference (or
     * {@link Conference#GID_NOT_SET} if it is not specified.
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    private @NotNull Conference createConference(
            @Nullable EntityBareJid name,
            String meetingId,
            boolean isRtcStatsEnabled,
            boolean isCallStatsEnabled,
            boolean checkForMeetingIdCollision)
    {
        final Conference conference
                = doCreateConference(
                        name, meetingId, isRtcStatsEnabled, isCallStatsEnabled, checkForMeetingIdCollision);

        logger.info(() -> "create_conf, id=" + conference.getID() + " meetingId=" + meetingId);

        eventEmitter.fireEvent(handler ->
        {
            handler.conferenceCreated(conference);
            return Unit.INSTANCE;
        });

        return conference;
    }

    /**
     * Expires a specific <tt>Conference</tt> of this <tt>Videobridge</tt> (i.e.
     * if the specified <tt>Conference</tt> is not in the list of
     * <tt>Conference</tt>s of this <tt>Videobridge</tt>, does nothing).
     *
     * @param conference the <tt>Conference</tt> to be expired by this
     * <tt>Videobridge</tt>
     */
    public void expireConference(Conference conference)
    {
        String id = conference.getID();
        String meetingId = conference.getMeetingId();

        synchronized (conferencesById)
        {
            if (conference.equals(conferencesById.get(id)))
            {
                conferencesById.remove(id);
                if (meetingId != null)
                {
                    if (conference.equals(conferencesByMeetingId.get(meetingId)))
                    {
                        conferencesByMeetingId.remove(meetingId);
                    }
                }
                conference.expire();
                eventEmitter.fireEvent(handler ->
                {
                    handler.conferenceExpired(conference);
                    return Unit.INSTANCE;
                });
            }
        }
    }

    /**
     * Generates a new <tt>Conference</tt> ID which is not guaranteed to be
     * unique.
     *
     * @return a new <tt>Conference</tt> ID which is not guaranteed to be unique
     */
    private String generateConferenceID()
    {
        return Long.toHexString(System.currentTimeMillis() + RANDOM.nextLong());
    }

    /**
     * Gets the statistics of this instance.
     *
     * @return the statistics of this instance.
     */
    public Statistics getStatistics()
    {
        return statistics;
    }

    /**
     * Gets an existing {@link Conference} with a specific ID.
     *
     * @param id the ID of the existing <tt>Conference</tt> to get
     * @return an existing <tt>Conference</tt> with the specified ID.
     */
    public Conference getConference(String id)
    {
        synchronized (conferencesById)
        {
            return conferencesById.get(id);
        }
    }

    /**
     * Gets an existing {@link Conference} with a specific meeting ID.
     */
    public Conference getConferenceByMeetingId(String meetingId)
    {
        /* Note that conferenceByMeetingId is synchronized on conferencesById. */
        synchronized (conferencesById)
        {
            return conferencesByMeetingId.get(meetingId);
        }
    }

    /**
     * Gets the <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public Collection<Conference> getConferences()
    {
        synchronized (conferencesById)
        {
            return new HashSet<>(conferencesById.values());
        }
    }

    /**
     * Handles a COLIBRI request synchronously.
     * @param conferenceIq The COLIBRI request.
     * @return The response in the form of an {@link IQ}. It is either an error or a {@link ColibriConferenceIQ}.
     */
    public IQ handleColibriConferenceIQ(ColibriConferenceIQ conferenceIq)
    {
        Conference conference;
        try
        {
            conference = getOrCreateConference(conferenceIq);
        }
        catch (ConferenceNotFoundException e)
        {
            return createConferenceNotFoundError(conferenceIq, conferenceIq.getID(), false);
        }
        catch (InGracefulShutdownException e)
        {
            return ColibriConferenceIQ.createGracefulShutdownErrorResponse(conferenceIq);
        }

        return conference.getShim().handleColibriConferenceIQ(conferenceIq);
    }

    /**
     * Handles a COLIBRI2 request synchronously.
     * @param conferenceModifyIQ The COLIBRI request.
     * @return The response in the form of an {@link IQ}. It is either an error or a {@link ConferenceModifiedIQ}.
     */
    public IQ handleConferenceModifyIq(ConferenceModifyIQ conferenceModifyIQ)
    {
        Conference conference;
        try
        {
            conference = getOrCreateConference(conferenceModifyIQ);
        }
        catch (ConferenceNotFoundException e)
        {
            return createConferenceNotFoundError(conferenceModifyIQ, conferenceModifyIQ.getMeetingId(), true);
        }
        catch (ConferenceAlreadyExistsException e)
        {
            return createConferenceAlreadyExistsError(conferenceModifyIQ, conferenceModifyIQ.getMeetingId(), true);
        }
        catch (InGracefulShutdownException e)
        {
            return ColibriConferenceIQ.createGracefulShutdownErrorResponse(conferenceModifyIQ);
        }
        catch (XmppStringprepException e)
        {
            return createError(
                    conferenceModifyIQ,
                    StanzaError.Condition.bad_request,
                    "Invalid conference name (not a JID)");
        }

        return conference.handleConferenceModifyIQ(conferenceModifyIQ);
    }

    /**
     * Handles a COLIBRI request asynchronously.
     */
    private void handleColibriRequest(XmppConnection.ColibriRequest request)
    {
        IQ iq = request.getRequest();
        String id = null;
        Conference conference;

        boolean colibri2;
        if (iq instanceof ConferenceModifyIQ)
        {
            colibri2 = true;
        }
        else if (iq instanceof ColibriConferenceIQ)
        {
            colibri2 = false;
        }
        else
        {
            throw new IllegalArgumentException("Bad IQ type " + iq.getClass().toString() + " in handleColibriRequest");
        }

        try
        {
            if (colibri2)
            {
                ConferenceModifyIQ conferenceModifyIq = (ConferenceModifyIQ) iq;
                id = conferenceModifyIq.getMeetingId();
                conference = getOrCreateConference(conferenceModifyIq);
            }
            else
            {
                ColibriConferenceIQ conferenceIq = (ColibriConferenceIQ) iq;
                id = conferenceIq.getID();
                conference = getOrCreateConference(conferenceIq);
            }
        }
        catch (ConferenceNotFoundException e)
        {
            request.getCallback().invoke(createConferenceNotFoundError(iq, id, colibri2));
            return;
        }
        catch (ConferenceAlreadyExistsException e)
        {
            request.getCallback().invoke(createConferenceAlreadyExistsError(iq, id, colibri2));
            return;
        }
        catch (InGracefulShutdownException e)
        {
            request.getCallback().invoke(ColibriConferenceIQ.createGracefulShutdownErrorResponse(iq));
            return;
        }
        catch (XmppStringprepException e)
        {
            request.getCallback().invoke(
                createError(iq, StanzaError.Condition.bad_request, "Invalid conference name (not a JID)"));
            return;
        }

        // It is now the responsibility of Conference to send a response.
        conference.enqueueColibriRequest(request);
    }

    private @NotNull Conference getOrCreateConference(ColibriConferenceIQ conferenceIq)
            throws ConferenceNotFoundException, InGracefulShutdownException
    {
        String conferenceId = conferenceIq.getID();
        if (conferenceId == null && isInGracefulShutdown())
        {
            throw new InGracefulShutdownException();
        }

        if (conferenceId == null)
        {
            return createConference(
                    conferenceIq.getName(),
                    conferenceIq.getMeetingId(),
                    conferenceIq.isRtcStatsEnabled(),
                    conferenceIq.isCallStatsEnabled(),
                    false);
        }
        else
        {
            Conference conference = getConference(conferenceId);
            if (conference == null)
            {
                throw new ConferenceNotFoundException();
            }
            return conference;
        }
    }

    private @NotNull Conference getOrCreateConference(ConferenceModifyIQ conferenceModifyIQ)
        throws InGracefulShutdownException, XmppStringprepException,
            ConferenceAlreadyExistsException, ConferenceNotFoundException
    {
        String meetingId = conferenceModifyIQ.getMeetingId();

        synchronized(conferencesById)
        {
            Conference conference = getConferenceByMeetingId(meetingId);

            if (conferenceModifyIQ.getCreate())
            {
                if (conference != null)
                {
                    logger.warn("Will not create conference, conference already exists for meetingId=" + meetingId);
                    throw new ConferenceAlreadyExistsException();
                }
                if (isInGracefulShutdown())
                {
                    logger.warn("Will not create conference in shutdown mode.");
                    throw new InGracefulShutdownException();
                }

                String conferenceName = conferenceModifyIQ.getConferenceName();
                return createConference(
                    conferenceName == null ? null : JidCreate.entityBareFrom(conferenceName),
                    meetingId,
                    conferenceModifyIQ.isRtcstatsEnabled(),
                    conferenceModifyIQ.isCallstatsEnabled(),
                    true);
            }
            else
            {
                if (conference == null)
                {
                    logger.warn("Conference with meetingId=" + meetingId + " not found.");
                    throw new ConferenceNotFoundException();
                }
                return conference;
            }
        }
    }

    /**
     * Handles <tt>HealthCheckIQ</tt> by performing health check on this
     * <tt>Videobridge</tt> instance.
     *
     * @param healthCheckIQ the <tt>HealthCheckIQ</tt> to be handled.
     * @return IQ with &quot;result&quot; type if the health check succeeded or
     * IQ with &quot;error&quot; type if something went wrong.
     * {@link StanzaError.Condition#internal_server_error} is returned when the
     * health check fails or {@link StanzaError.Condition#not_authorized} if the
     * request comes from a JID that is not authorized to do health checks on
     * this instance.
     */
    public IQ handleHealthCheckIQ(HealthCheckIQ healthCheckIQ)
    {
        try
        {
            return IQ.createResultIQ(healthCheckIQ);
        }
        catch (Exception e)
        {
            logger.warn("Exception while handling health check IQ request", e);
            return createError(healthCheckIQ, StanzaError.Condition.internal_server_error, e.getMessage());
        }
    }

    /**
     * Handles a shutdown request.
     */
    public void shutdown(boolean graceful)
    {
        shutdownManager.initiateShutdown(graceful);
        shutdownManager.maybeShutdown(statistics.currentLocalEndpoints.get());
    }

    /**
     * Whether the bridge is in "drain" mode. The bridge will not be assigned to new
     * conferences when in this mode.
     */
    private boolean drainMode = VideobridgeConfig.Companion.getInitialDrainMode();

    /**
     * Handles a drain request.
     */
    public void setDrainMode(boolean enable)
    {
        logger.info("Received drain request. enable=" + enable);
        drainMode = enable;
    }

    /**
     * Query the drain state.
     */
    public boolean getDrainMode()
    {
        return drainMode;
    }

    /**
     * Returns {@code true} if this instance has entered graceful shutdown mode.
     *
     * @return {@code true} if this instance has entered graceful shutdown mode;
     * otherwise, {@code false}
     */
    public boolean isInGracefulShutdown()
    {
        return shutdownManager.getState() == ShutdownState.GRACEFUL_SHUTDOWN;
    }

    public ShutdownState getShutdownState()
    {
        return shutdownManager.getState();
    }

    /**
     * Starts this {@link Videobridge}.
     *
     * NOTE: we have to make this public so Jicofo can call it from its tests.
     */
    public void start()
    {
        UlimitCheck.printUlimits();

        videobridgeExpireThread.start();

        // <conference>
        ProviderManager.addIQProvider(
                ColibriConferenceIQ.ELEMENT,
                ColibriConferenceIQ.NAMESPACE,
                new ColibriConferenceIqProvider());
        
        // <force-shutdown>
        ForcefulShutdownIqProvider.registerIQProvider();

        // <graceful-shutdown>
        GracefulShutdownIqProvider.registerIQProvider();

        // <stats>
        new ColibriStatsIqProvider(); // registers itself with Smack

        // ICE-UDP <transport>
        ProviderManager.addExtensionProvider(
                IceUdpTransportPacketExtension.ELEMENT,
                IceUdpTransportPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(IceUdpTransportPacketExtension.class));

        // RAW-UDP <candidate xmlns=urn:xmpp:jingle:transports:raw-udp:1>
        DefaultPacketExtensionProvider<UdpCandidatePacketExtension> udpCandidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(UdpCandidatePacketExtension.class);
        ProviderManager.addExtensionProvider(
                UdpCandidatePacketExtension.ELEMENT,
                UdpCandidatePacketExtension.NAMESPACE,
                udpCandidatePacketExtensionProvider);

        // ICE-UDP <candidate xmlns=urn:xmpp:jingle:transports:ice-udp:1">
        DefaultPacketExtensionProvider<IceCandidatePacketExtension> iceCandidatePacketExtensionProvider
                = new DefaultPacketExtensionProvider<>(IceCandidatePacketExtension.class);
        ProviderManager.addExtensionProvider(
                IceCandidatePacketExtension.ELEMENT,
                IceCandidatePacketExtension.NAMESPACE,
                iceCandidatePacketExtensionProvider);

        // ICE <rtcp-mux/>
        ProviderManager.addExtensionProvider(
                IceRtcpmuxPacketExtension.ELEMENT,
                IceRtcpmuxPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(IceRtcpmuxPacketExtension.class));

        // DTLS-SRTP <fingerprint>
        ProviderManager.addExtensionProvider(
                DtlsFingerprintPacketExtension.ELEMENT,
                DtlsFingerprintPacketExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(DtlsFingerprintPacketExtension.class));

        // Health-check
        HealthCheckIQProvider.registerIQProvider();

        // Colibri2
        IqProviderUtils.registerProviders();
    }

    /**
     * Stops this {@link Videobridge}.
     *
     * NOTE: we have to make this public so Jicofo can call it from its tests.
     */
    public void stop()
    {
        videobridgeExpireThread.stop();
        if (loadSamplerTask != null)
        {
            loadSamplerTask.cancel(true);
        }
    }

    /**
     * Creates a JSON for debug purposes. If a specific {@code conferenceId}
     * is requested, the result will include all of the state of the conference
     * considered useful for debugging (note that this is a LOT of data).
     * Otherwise (if {@code conferenceId} is {@code null}), the result includes
     * a shallow list of the active conferences and their endpoints.
     *
     * @param conferenceId the ID of a specific conference to include. If not
     * specified, a shallow list of all conferences will be returned.
     * @param endpointId the ID of a specific endpoint in {@code conferenceId}
     * to include. If not specified, all of the conference's endpoints will be
     * included.
     */
    @SuppressWarnings("unchecked")
    public OrderedJsonObject getDebugState(String conferenceId, String endpointId, boolean full)
    {
        OrderedJsonObject debugState = new OrderedJsonObject();
        debugState.put("shutdownState", shutdownManager.getState().toString());
        debugState.put("drain", drainMode);
        debugState.put("time", System.currentTimeMillis());

        debugState.put("load-management", jvbLoadManager.getStats());
        debugState.put("overall_bridge_jitter", PacketTransitStats.getBridgeJitter());

        JSONObject conferences = new JSONObject();
        debugState.put("conferences", conferences);
        if (StringUtils.isBlank(conferenceId))
        {
            getConferences().forEach(conference ->
                conferences.put(
                        conference.getID(),
                        conference.getDebugState(full, null)));
        }
        else
        {
            // Using getConference will 'touch' it and prevent it from expiring
            Conference conference;
            synchronized (conferences)
            {
                conference = this.conferencesById.get(conferenceId);
            }

            conferences.put(
                    conferenceId,
                    conference == null ? "null" : conference.getDebugState(full, endpointId));
        }

        return debugState;
    }

    /**
     * Gets statistics for the different {@code PacketQueue}s that this bridge
     * uses.
     * TODO: is there a better place for this?
     */
    @SuppressWarnings("unchecked")
    public JSONObject getQueueStats()
    {
        JSONObject queueStats = new JSONObject();

        queueStats.put(
            "srtp_send_queue",
            getJsonFromQueueStatisticsAndErrorHandler(Endpoint.queueErrorCounter,
                "Endpoint-outgoing-packet-queue"));
        queueStats.put(
            "relay_srtp_send_queue",
            getJsonFromQueueStatisticsAndErrorHandler(Relay.queueErrorCounter,
                "Relay-outgoing-packet-queue"));
        queueStats.put(
            "relay_endpoint_sender_srtp_send_queue",
            getJsonFromQueueStatisticsAndErrorHandler(RelayEndpointSender.queueErrorCounter,
                "RelayEndpointSender-outgoing-packet-queue"));
        queueStats.put(
            "rtp_receiver_queue",
            getJsonFromQueueStatisticsAndErrorHandler(RtpReceiverImpl.Companion.getQueueErrorCounter(),
                "rtp-receiver-incoming-packet-queue"));
        queueStats.put(
            "rtp_sender_queue",
            getJsonFromQueueStatisticsAndErrorHandler(RtpSenderImpl.Companion.getQueueErrorCounter(),
                "rtp-sender-incoming-packet-queue"));
        queueStats.put(
            "colibri_queue",
            QueueStatistics.Companion.getStatistics().get("colibri-queue")
        );

        queueStats.put(
            AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID,
            getJsonFromQueueStatisticsAndErrorHandler(
                    null,
                    AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID));

        return queueStats;
    }

    private OrderedJsonObject getJsonFromQueueStatisticsAndErrorHandler(
            CountingErrorHandler countingErrorHandler,
            String queueName)
    {
        OrderedJsonObject json = (OrderedJsonObject)QueueStatistics.Companion.getStatistics().get(queueName);
        if (countingErrorHandler != null)
        {
            if (json == null)
            {
                json = new OrderedJsonObject();
                json.put("dropped_packets", countingErrorHandler.getNumPacketsDropped());
            }
            json.put("exceptions", countingErrorHandler.getNumExceptions());
        }

        return json;
    }

    @NotNull
    public Version getVersion()
    {
        return version;
    }

    /**
     * Get the release ID of this videobridge.
     * @return The release ID. Returns null if not in use.
     */
    public @Nullable String getReleaseId()
    {
        return releaseId;
    }

    public void addEventHandler(EventHandler eventHandler)
    {
        eventEmitter.addHandler(eventHandler);
    }

    public void removeEventHandler(EventHandler eventHandler)
    {
        eventEmitter.removeHandler(eventHandler);
    }

    private class XmppConnectionEventHandler implements XmppConnection.EventHandler
    {
        @Override
        public void colibriConferenceIqReceived(@NotNull XmppConnection.ColibriRequest request)
        {
            handleColibriRequest(request);
        }

        @NotNull
        @Override
        public IQ versionIqReceived(@NotNull org.jivesoftware.smackx.iqversion.packet.Version iq)
        {
            org.jivesoftware.smackx.iqversion.packet.Version versionResult =
                new org.jivesoftware.smackx.iqversion.packet.Version(
                    version.getApplicationName(),
                    version.toString(),
                    System.getProperty("os.name")
                );

            // to, from and packetId are set by the caller.
            // versionResult.setTo(versionRequest.getFrom());
            // versionResult.setFrom(versionRequest.getTo());
            // versionResult.setPacketID(versionRequest.getPacketID());
            versionResult.setType(IQ.Type.result);

            return versionResult;
        }

        @NotNull
        @Override
        public IQ healthCheckIqReceived(@NotNull HealthCheckIQ iq)
        {
            return handleHealthCheckIQ(iq);
        }
    }

    /**
     * Basic statistics/metrics about the videobridge like cumulative/total
     * number of channels created, cumulative/total number of channels failed,
     * etc.
     */
    public static class Statistics
    {
        /**
         * The total number of times our AIMDs have expired the incoming bitrate
         * (and which would otherwise result in video suspension).
         * (see {@link AimdRateControl#incomingBitrateExpirations}).
         */
        public AtomicInteger incomingBitrateExpirations = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences that had all of their
         * channels failed because there was no transport activity (which
         * includes those that failed because there was no payload activity).
         */
        public AtomicInteger totalFailedConferences = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences that had some of their
         * channels failed because there was no transport activity (which
         * includes those that failed because there was no payload activity).
         */
        public AtomicInteger totalPartiallyFailedConferences = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences completed/expired on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalConferencesCompleted = new AtomicInteger(0);

        /**
         * The cumulative/total number of conferences created on this
         * {@link Videobridge}.
         */
        public AtomicInteger totalConferencesCreated = new AtomicInteger(0);

        /**
         * The total duration in seconds of all completed conferences on this
         * {@link Videobridge}.
         */
        public AtomicLong totalConferenceSeconds = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-controlled
         * (i.e. the sum of the lengths is seconds) on this {@link Videobridge}.
         */
        public AtomicLong totalLossControlledParticipantMs = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-limited
         * on this {@link Videobridge}.
         */
        public AtomicLong totalLossLimitedParticipantMs = new AtomicLong();

        /**
         * The total number of participant-milliseconds that are loss-degraded
         * on this {@link Videobridge}. We chose the unit to be millis because
         * we expect that a lot of our calls spend very few ms (<500) in the
         * lossDegraded state for example, and they might get cut to 0.
         */
        public AtomicLong totalLossDegradedParticipantMs = new AtomicLong();

        /**
         * The total number of times an ICE Agent failed to establish
         * connectivity.
         */
        public AtomicInteger totalIceFailed = new AtomicInteger();

        /**
         * The total number of times an ICE Agent succeeded.
         */
        public AtomicInteger totalIceSucceeded = new AtomicInteger();

        /**
         * The total number of times an ICE Agent succeeded and the selected
         * candidate was a TCP candidate.
         */
        public AtomicInteger totalIceSucceededTcp = new AtomicInteger();

        /**
         * The total number of times an ICE Agent succeeded and the selected
         * candidate pair included a relayed candidate.
         */
        public AtomicInteger totalIceSucceededRelayed = new AtomicInteger();

        /**
         * The total number of messages received from the data channels of
         * the endpoints of this conference.
         */
        public AtomicLong totalDataChannelMessagesReceived = new AtomicLong();

        /**
         * The total number of messages sent via the data channels of the
         * endpoints of this conference.
         */
        public AtomicLong totalDataChannelMessagesSent = new AtomicLong();

        /**
         * The total number of messages received from the data channels of
         * the endpoints of this conference.
         */
        public AtomicLong totalColibriWebSocketMessagesReceived = new AtomicLong();

        /**
         * The total number of messages sent via the data channels of the
         * endpoints of this conference.
         */
        public AtomicLong totalColibriWebSocketMessagesSent = new AtomicLong();

        /**
         * The total number of bytes received in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets sent in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalPacketsSent = new AtomicLong();

        /**
         * The total number of bytes received by relays in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalRelayBytesReceived = new AtomicLong();

        /**
         * The total number of bytes sent by relays in RTP packets in conferences on
         * this videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalRelayBytesSent = new AtomicLong();

        /**
         * The total number of RTP packets received by relays in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalRelayPacketsReceived = new AtomicLong();

        /**
         * The total number of RTP packets sent by relays in conferences on this
         * videobridge. Note that this is only updated when conferences
         * expire.
         */
        public AtomicLong totalRelayPacketsSent = new AtomicLong();

        /**
         * The total number of endpoints created.
         */
        public AtomicInteger totalEndpoints = new AtomicInteger();

        /**
         * The number of endpoints which had not established an endpoint
         * message transport even after some delay.
         */
        public AtomicInteger numEndpointsNoMessageTransportAfterDelay = new AtomicInteger();

        /**
         * The total number of relays created.
         */
        public AtomicInteger totalRelays = new AtomicInteger();

        /**
         * The number of relays which had not established a relay
         * message transport even after some delay.
         */
        public AtomicInteger numRelaysNoMessageTransportAfterDelay = new AtomicInteger();

        /**
         * The total number of times the dominant speaker in any conference
         * changed.
         */
        public LongAdder totalDominantSpeakerChanges = new LongAdder();

        /**
         * Number of endpoints whose ICE connection was established, but DTLS
         * wasn't (at the time of expiration).
         */
        public AtomicInteger dtlsFailedEndpoints = new AtomicInteger();

        /**
         * The stress level for this bridge
         */
        public Double stressLevel = 0.0;

        /** Distribution of energy scores for discarded audio packets  */
        public BucketStats tossedPacketsEnergy = new BucketStats(
                Stream.iterate(0L, n -> n + 1).limit(17)
                        .map(w -> Math.max(8 * w - 1, 0))
                        .collect(Collectors.toList()),
                "", "");

        /** Number of preemptive keyframe requests that were sent. */
        public AtomicInteger preemptiveKeyframeRequestsSent = new AtomicInteger();

        /** Number of preemptive keyframe requests that were not sent because no endpoints were in stage view. */
        public AtomicInteger preemptiveKeyframeRequestsSuppressed = new AtomicInteger();

        /** The total number of keyframes that were received (updated on endpoint expiration). */
        public AtomicInteger totalKeyframesReceived = new AtomicInteger();

        /**
         * The total number of times the layering of an incoming video stream changed (updated on endpoint expiration).
         */
        public AtomicInteger totalLayeringChangesReceived = new AtomicInteger();

        /**
         * The total duration, in milliseconds, of video streams (SSRCs) that were received. For example, if an
         * endpoint sends simulcast with 3 SSRCs for 1 minute it would contribute a total of 3 minutes. Suspended
         * streams do not contribute to this duration.
         *
         * This is updated on endpoint expiration.
         */
        public AtomicLong totalVideoStreamMillisecondsReceived = new AtomicLong();

        /**
         * Number of local endpoints that exist currently.
         */
        public AtomicLong currentLocalEndpoints = new AtomicLong();
    }

    public interface EventHandler {
        void conferenceCreated(@NotNull Conference conference);
        void conferenceExpired(@NotNull Conference conference);
    }
    private static class ConferenceNotFoundException extends Exception {}
    private static class ConferenceAlreadyExistsException extends Exception {}
    private static class InGracefulShutdownException extends Exception {}
}
