/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jicofo.recording.jibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.recording.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Handles conference recording through Jibri.
 * Waits for updates from {@link JibriDetector} about recorder instance
 * availability and publishes that information in Jicofo's MUC presence.
 * Handles incoming Jibri IQs coming from conference moderator to
 * start/stop the recording.
 *
 * @author Pawel Domas
 * @author Sam Whited
 */
public class JibriRecorder
    extends Recorder
    implements JibriSessionOwner
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger
        = Logger.getLogger(JibriRecorder.class);

    /**
     * Returns <tt>true</tt> if given <tt>status</tt> indicates that Jibri is in
     * the middle of starting of the recording process.
     */
    static private boolean isStartingStatus(JibriIq.Status status)
    {
        return JibriIq.Status.PENDING.equals(status)
            || JibriIq.Status.RETRYING.equals(status);
    }

    //private final Jibri jibri;
    private JibriSession jibriSession;

    /**
     * Recorded <tt>JitsiMeetConferenceImpl</tt>.
     */
    private final JitsiMeetConferenceImpl conference;

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    /**
     * Jibri detector which notifies about Jibri status changes.
     */
    private final JibriDetector jibriDetector;

    /**
     * Current Jibri recording status.
     */
    private JibriIq.Status jibriStatus = JibriIq.Status.UNDEFINED;

    /**
     * Meet tools instance used to inject packet extensions to Jicofo's MUC
     * presence.
     */
    private final OperationSetJitsiMeetTools meetTools;


    /**
     * Executor service for used to schedule pending timeout tasks.
     */
    private final ScheduledExecutorService scheduledExecutor;

    /**
     * The global config used by this instance.
     */
    private final JitsiMeetGlobalConfig globalConfig;

    /**
     * Helper class that registers for {@link JibriEvent}s in the OSGi context
     * obtained from the {@link FocusBundleActivator}.
     */
    private final JibriEventHandler jibriEventHandler = new JibriEventHandler();

    /**
     * Creates new instance of <tt>JibriRecorder</tt>.
     * @param conference <tt>JitsiMeetConference</tt> to be recorded by new
     *        instance.
     * @param xmpp XMPP operation set which wil be used to send XMPP queries.
     * @param scheduledExecutor the executor service used by this instance
     * @param globalConfig the global config that provides some values required
     *                     by <tt>JibriRecorder</tt> to work.
     */
    public JibriRecorder(JitsiMeetConferenceImpl         conference,
                         OperationSetDirectSmackXmpp     xmpp,
                         ScheduledExecutorService        scheduledExecutor,
                         JitsiMeetGlobalConfig           globalConfig)
    {
        super(null, xmpp);

        this.conference = Objects.requireNonNull(conference, "conference");
        this.scheduledExecutor
            = Objects.requireNonNull(scheduledExecutor, "scheduledExecutor");
        this.globalConfig = Objects.requireNonNull(globalConfig, "globalConfig");

        jibriDetector
            = conference.getServices().getJibriDetector();

        ProtocolProviderService protocolService = conference.getXmppProvider();

        meetTools
            = protocolService.getOperationSet(OperationSetJitsiMeetTools.class);

        logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * Starts listening for Jibri updates and initializes Jicofo presence.
     *
     * {@inheritDoc}
     */
    @Override
    public void init()
    {
        super.init();

        try
        {
            jibriEventHandler.start(FocusBundleActivator.bundleContext);
        }
        catch (Exception e)
        {
            logger.error("Failed to start Jibri event handler: " + e, e);
        }

        updateJibriAvailability();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose()
    {
        try
        {
            jibriEventHandler.stop(FocusBundleActivator.bundleContext);
        }
        catch (Exception e)
        {
            logger.error("Failed to stop Jibri event handler: " + e, e);
        }

        if (this.jibriSession != null)
        {
            this.jibriSession.stop();
        }

        super.dispose();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecording()
    {
        return jibriSession != null;
    }

    /**
     * Not implemented in Jibri Recorder
     *
     * {@inheritDoc}
     */
    @Override
    public boolean setRecording(String from, String token,
                                ColibriConferenceIQ.Recording.State doRecord,
                                String path)
    {
        // NOT USED

        return false;
    }

    private String getRoomName()
    {
        return conference.getRoomName();
    }


    /**
     * <tt>JibriIq</tt> processing.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void processPacket(Packet packet)
    {
        //if (logger.isDebugEnabled())
        //{
            logger.info(
                "Processing an IQ: " + packet.toXML());
        //}

        IQ iq = (IQ) packet;

        String from = iq.getFrom();

        JibriIq jibriIq = (JibriIq) iq;

        String roomName = MucUtil.extractRoomNameFromMucJid(from);
        if (roomName == null)
        {
            logger.warn("Could not extract room name from jid:" + from);
            return;
        }

        String actualRoomName = getRoomName();
        if (!actualRoomName.equals(roomName))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Ignored packet from: " + roomName
                    + ", my room: " + actualRoomName
                    + " p: " + packet.toXML());
            }
            return;
        }

        XmppChatMember chatMember = conference.findMember(from);
        if (chatMember == null)
        {
            logger.warn("ERROR chat member not found for: " + from
                + " in " + roomName);
            return;
        }

        processJibriIqFromMeet(jibriIq, chatMember);
    }

    /**
     * Accepts only {@link JibriIq}
     * {@inheritDoc}
     */
    @Override
    public boolean accept(Packet packet)
    {
        // Do not process if it belongs to the recording session
        // FIXME should accept only packets coming from MUC
        return !(jibriSession != null && jibriSession.accept(packet))
            && packet instanceof JibriIq
            && StringUtils.isNullOrEmpty(((JibriIq)packet).getSipAddress());
    }

    /**
     * The method is supposed to update Jibri availability status to OFF if we
     * have any Jibris available or to UNDEFINED if there are no any.
     */
    private void updateJibriAvailability()
    {
        if (jibriSession != null)
            return;

        if (jibriDetector.selectJibri() != null)
        {
            setJibriStatus(JibriIq.Status.OFF);
        }
        else if (jibriDetector.isAnyJibriConnected())
        {
            setJibriStatus(JibriIq.Status.BUSY);
        }
        else
        {
            setJibriStatus(JibriIq.Status.UNDEFINED);
        }
    }

    private void setJibriStatus(JibriIq.Status newStatus)
    {
        setJibriStatus(newStatus, null);
    }

    @Override
    public void onSessionStateChanged(
        JibriSession jibriSession, JibriIq.Status newStatus, XMPPError error)
    {
        if (this.jibriSession != jibriSession)
        {
            logger.error(
                "onSessionStateChanged for unknown session: " + jibriSession);
            return;
        }

        // FIXME
        boolean recordingStopped
            = JibriIq.Status.FAILED.equals(newStatus) ||
                JibriIq.Status.OFF.equals(newStatus);

        setJibriStatus(newStatus, error);

        if (recordingStopped)
        {
            this.jibriSession = null;
            updateJibriAvailability();
        }
    }

    private void setJibriStatus(JibriIq.Status newStatus, XMPPError error)
    {
        jibriStatus = newStatus;

        RecordingStatus recordingStatus = new RecordingStatus();

        recordingStatus.setStatus(newStatus);

        recordingStatus.setError(error);

        logger.info(
            "Publish new JIBRI status: "
                + recordingStatus.toXML() + " in: " + getRoomName());

        ChatRoom2 chatRoom2 = conference.getChatRoom();

        // Publish that in the presence
        if (chatRoom2 != null)
        {
            meetTools.sendPresenceExtension(chatRoom2, recordingStatus);
        }
    }

    private void processJibriIqFromMeet(final JibriIq           iq,
                                        final XmppChatMember    sender)
    {
        String senderMucJid = sender.getContactAddress();
        //if (logger.isDebugEnabled())
        //{
            logger.info(
                "Jibri request from " + senderMucJid
                    + " iq: " + iq.toXML());
        //}

        JibriIq.Action action = iq.getAction();
        if (JibriIq.Action.UNDEFINED.equals(action))
            return;

        // verifyModeratorRole sends 'not_allowed' error on false
        if (!verifyModeratorRole(iq))
        {
            logger.warn(
                "Ignored Jibri request from non-moderator: " + senderMucJid);
            return;
        }

        // start ?
        if (JibriIq.Action.START.equals(action) &&
            JibriIq.Status.OFF.equals(jibriStatus) &&
            jibriSession == null)
        {
            // Store stream ID
            String streamID = iq.getStreamId();
            String displayName = iq.getDisplayName();
            String sipAddress = iq.getSipAddress();
            // Proceed if not empty
            if (!StringUtils.isNullOrEmpty(streamID))
            {
                // ACK the request immediately to simplify the flow,
                // any error will be passed with the FAILED state
                sendResultResponse(iq);
                jibriSession
                    = new JibriSession(
                            this,
                            conference,
                            globalConfig,
                            xmpp,
                            scheduledExecutor,
                            jibriDetector,
                            false, sipAddress, displayName, streamID,
                            classLogger);
                // Try starting Jibri on separate thread with retries
                jibriSession.start();
                return;
            }
            else
            {
                // Bad request - no stream ID
                sendErrorResponse(
                    iq,
                    XMPPError.Condition.bad_request,
                    "Stream ID is empty or undefined");
                return;
            }
        }
        // stop ?
        else if (JibriIq.Action.STOP.equals(action) &&
            jibriSession != null &&
            (JibriIq.Status.ON.equals(jibriStatus) ||
                isStartingStatus(jibriStatus)))
        {
            // XXX FIXME: this is synchronous and will probably block the smack
            // thread that executes processPacket().
            //try
            //{
                XMPPError error = jibriSession.stop();
                sendPacket(
                    error == null
                        ? IQ.createResultIQ(iq)
                        : IQ.createErrorResponse(iq, error));
            //}
            //catch (OperationFailedException e)
            //{
                // XXX the XMPP connection is broken
                // This instance shall be disposed soon after
                //logger.error("Failed to send stop IQ - XMPP disconnected", e);
                // FIXME deal with it
                //recordingStopped(null, false /* do not send status update */);
            //}
            return;
        }

        logger.warn(
            "Discarded: " + iq.toXML() + " - nothing to be done, "
                + "recording status:" + jibriStatus);

        // Bad request
        sendErrorResponse(
            iq, XMPPError.Condition.bad_request,
            "Unable to handle: '" + action
                + "' in state: '" + jibriStatus + "'");
    }

    private boolean verifyModeratorRole(JibriIq iq)
    {
        String from = iq.getFrom();
        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            sendErrorResponse(iq, XMPPError.Condition.forbidden, null);
            return false;
        }

        if (ChatRoomMemberRole.MODERATOR.compareTo(role) < 0)
        {
            // Moderator permission is required
            sendErrorResponse(iq, XMPPError.Condition.not_allowed, null);
            return false;
        }
        return true;
    }

    private void sendPacket(Packet packet)
    {
        xmpp.getXmppConnection().sendPacket(packet);
    }

    private void sendResultResponse(IQ request)
    {
        sendPacket(
            IQ.createResultIQ(request));
    }

    private void sendErrorResponse(IQ request,
                                   XMPPError.Condition condition,
                                   String msg)
    {
        sendPacket(
            IQ.createErrorResponse(
                request,
                new XMPPError(condition, msg)
            )
        );
    }

    // FIXME jibriJid and idle are not really used ?
    synchronized private void onJibriStatusChanged(String    jibriJid,
                                                   boolean   idle)
    {
        // We listen to status updates coming from our Jibri through IQs
        // if recording is in progress(recorder JID is not null),
        // otherwise it is fine to update Jibri recording availability here
        if (jibriSession == null)
        {
            updateJibriAvailability();
        }
    }

    synchronized private void onJibriOffline(final String jibriJid)
    {
        if (jibriSession == null)
        {
            updateJibriAvailability();
        }
    }

    /**
     * Helper class handles registration for the {@link JibriEvent}s.
     */
    private class JibriEventHandler
        extends EventHandlerActivator
    {

        private JibriEventHandler()
        {
            super(new String[]{
                JibriEvent.STATUS_CHANGED, JibriEvent.WENT_OFFLINE});
        }

        @Override
        public void handleEvent(Event event)
        {
            if (!JibriEvent.isJibriEvent(event))
            {
                logger.error("Invalid event: " + event);
                return;
            }

            final JibriEvent jibriEvent = (JibriEvent) event;
            final String topic = jibriEvent.getTopic();
            final boolean isSIP = jibriEvent.isSIP();
            if (isSIP)
            {
                return;
            }

            switch (topic)
            {
                case JibriEvent.WENT_OFFLINE:
                case JibriEvent.STATUS_CHANGED:
                    updateJibriAvailability();
                    break;
                default:
                    logger.error("Invalid topic: " + topic);
            }
        }
    }
}
