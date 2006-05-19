/*
 *  Copyright 2004-2006 Stefan Reuter
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.asteriskjava.live.internal;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.asteriskjava.live.AsteriskChannel;
import org.asteriskjava.live.AsteriskManager;
import org.asteriskjava.live.AsteriskQueue;
import org.asteriskjava.live.ManagerCommunicationException;
import org.asteriskjava.manager.AuthenticationFailedException;
import org.asteriskjava.manager.EventTimeoutException;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.ManagerEventListener;
import org.asteriskjava.manager.ResponseEvents;
import org.asteriskjava.manager.TimeoutException;
import org.asteriskjava.manager.action.CommandAction;
import org.asteriskjava.manager.action.OriginateAction;
import org.asteriskjava.manager.action.QueueStatusAction;
import org.asteriskjava.manager.action.StatusAction;
import org.asteriskjava.manager.event.ConnectEvent;
import org.asteriskjava.manager.event.DisconnectEvent;
import org.asteriskjava.manager.event.HangupEvent;
import org.asteriskjava.manager.event.JoinEvent;
import org.asteriskjava.manager.event.LeaveEvent;
import org.asteriskjava.manager.event.LinkEvent;
import org.asteriskjava.manager.event.ManagerEvent;
import org.asteriskjava.manager.event.NewCallerIdEvent;
import org.asteriskjava.manager.event.NewChannelEvent;
import org.asteriskjava.manager.event.NewExtenEvent;
import org.asteriskjava.manager.event.NewStateEvent;
import org.asteriskjava.manager.event.OriginateEvent;
import org.asteriskjava.manager.event.QueueEntryEvent;
import org.asteriskjava.manager.event.QueueMemberEvent;
import org.asteriskjava.manager.event.QueueParamsEvent;
import org.asteriskjava.manager.event.RenameEvent;
import org.asteriskjava.manager.event.ResponseEvent;
import org.asteriskjava.manager.event.StatusEvent;
import org.asteriskjava.manager.event.UnlinkEvent;
import org.asteriskjava.manager.response.CommandResponse;
import org.asteriskjava.manager.response.ManagerResponse;
import org.asteriskjava.util.Log;
import org.asteriskjava.util.LogFactory;

/**
 * Default implementation of the AsteriskManager interface.
 * 
 * @see org.asteriskjava.live.AsteriskManager
 * @author srt
 * @version $Id: DefaultAsteriskManager.java 306 2006-05-19 15:29:14Z srt $
 */
public class AsteriskManagerImpl
        implements
            AsteriskManager,
            ManagerEventListener
{
    private static final Pattern SHOW_VERSION_FILES_PATTERN = Pattern
            .compile("^([\\S]+)\\s+Revision: ([0-9\\.]+)");

    private final Log logger = LogFactory.getLog(this.getClass());

    /**
     * The underlying manager connection used to receive events from Asterisk.
     */
    private ManagerConnection eventConnection;

    /**
     * A pool of manager connections to use for sending actions to Asterisk.
     */
    private final ManagerConnectionPool connectionPool;

    private final ChannelManager channelManager;
    private final QueueManager queueManager;

    /**
     * The version of the Asterisk server we are connected to.<br>
     * Contains <code>null</code> until lazily initialized.
     */
    private String version;

    /**
     * Holds the version of Asterisk's source files.<br>
     * That corresponds to the output of the CLI command
     * <code>show version files</code>.<br>
     * Contains <code>null</code> until lazily initialized.
     */
    private Map<String, String> versions;

    /**
     * Flag to skip initializing queues as that results in a timeout on Asterisk
     * 1.0.x.
     */
    private boolean skipQueues;

    /**
     * Creates a new instance.
     */
    public AsteriskManagerImpl()
    {
        connectionPool = new ManagerConnectionPool(1);
        channelManager = new ChannelManager(connectionPool);
        queueManager = new QueueManager(channelManager);
    }

    /**
     * Creates a new instance.
     * 
     * @param eventConnection the ManagerConnection to use for receiving events from Asterisk.
     */
    public AsteriskManagerImpl(ManagerConnection eventConnection)
    {
        this();
        this.eventConnection = eventConnection;
        this.connectionPool.add(eventConnection);
    }

    /**
     * Determines if queue status is retrieved at startup. If you don't need
     * queue information and still run Asterisk 1.0.x you can set this to
     * <code>true</code> to circumvent the startup delay caused by the missing
     * QueueStatusComplete event.<br>
     * Default is <code>false</code>.
     * 
     * @param skipQueues <code>true</code> to skip queue initialization,
     *            <code>false</code> to not skip.
     * @since 0.2
     */
    public void setSkipQueues(boolean skipQueues)
    {
        this.skipQueues = skipQueues;
    }

    public void setManagerConnection(ManagerConnection eventConnection)
    {
        this.eventConnection = eventConnection;
        this.connectionPool.clear();
        this.connectionPool.add(eventConnection);
    }

    public void initialize() throws TimeoutException, IOException,
            AuthenticationFailedException
    {
        if (!eventConnection.isConnected())
        {
            eventConnection.login();
        }

        initializeChannels();
        initializeQueues();

        eventConnection.addEventListener(this);
    }

    private void initializeChannels() throws EventTimeoutException, IOException
    {
        ResponseEvents re;

        re = eventConnection.sendEventGeneratingAction(new StatusAction());
        for (ManagerEvent event : re.getEvents())
        {
            if (event instanceof StatusEvent)
            {
                channelManager.handleStatusEvent((StatusEvent) event);
            }
        }
    }

    private void initializeQueues() throws IOException
    {
        ResponseEvents re;

        if (skipQueues)
        {
            return;
        }

        try
        {
            re = eventConnection.sendEventGeneratingAction(new QueueStatusAction());
        }
        catch (EventTimeoutException e)
        {
            // this happens with Asterisk 1.0.x as it doesn't send a
            // QueueStatusCompleteEvent
            re = e.getPartialResult();
        }

        for (ManagerEvent event : re.getEvents())
        {
            if (event instanceof QueueParamsEvent)
            {
                queueManager.handleQueueParamsEvent((QueueParamsEvent) event);
            }
            else if (event instanceof QueueMemberEvent)
            {
                queueManager.handleQueueMemberEvent((QueueMemberEvent) event);
            }
            else if (event instanceof QueueEntryEvent)
            {
                queueManager.handleQueueEntryEvent((QueueEntryEvent) event);
            }
        }
    }

    /* Implementation of the AsteriskManager interface */

    public AsteriskChannel originateToExtension(String channel, String context, String exten, int priority, long timeout) throws ManagerCommunicationException
    {
        return originateToExtension(channel, context, exten, priority, timeout, null);
    }

    public AsteriskChannel originateToExtension(String channel, String context, String exten, int priority, long timeout, Map<String, String> variables) throws ManagerCommunicationException
    {
        OriginateAction originateAction;

        originateAction = new OriginateAction();
        originateAction.setChannel(channel);
        originateAction.setContext(context);
        originateAction.setExten(exten);
        originateAction.setPriority(priority);
        originateAction.setTimeout(timeout);
        originateAction.setVariables(variables);

        // must set async to true to receive OriginateEvents.
        originateAction.setAsync(Boolean.TRUE);
        
        return originate(originateAction);
    }

    public AsteriskChannel originateToApplication(String channel, String application, String data, long timeout) throws ManagerCommunicationException
    {
        return originateToApplication(channel, application, data, timeout, null);
    }

    public AsteriskChannel originateToApplication(String channel, String application, String data, long timeout, Map<String, String> variables) throws ManagerCommunicationException
    {
        OriginateAction originateAction;

        originateAction = new OriginateAction();
        originateAction.setChannel(channel);
        originateAction.setApplication(application);
        originateAction.setData(data);
        originateAction.setTimeout(timeout);
        originateAction.setVariables(variables);
        
        // must set async to true to receive OriginateEvents.
        originateAction.setAsync(Boolean.TRUE);
        
        return originate(originateAction);
    }

    private AsteriskChannel originate(OriginateAction originateAction) throws ManagerCommunicationException
    {
        ResponseEvents responseEvents;
        Iterator<ResponseEvent> responseEventIterator;
        
        // 2000 ms extra for the OriginateFailureEvent should be fine
        responseEvents = connectionPool.sendEventGeneratingAction(originateAction,
                originateAction.getTimeout() + 2000);
            
        responseEventIterator = responseEvents.getEvents().iterator();
        if (responseEventIterator.hasNext())
        {
            ResponseEvent responseEvent;
            
            responseEvent = responseEventIterator.next();
            if (responseEvent instanceof OriginateEvent)
            {
                return getChannelById(((OriginateEvent) responseEvent).getUniqueId()); 
            }
        }

        return null;
    }

    public Collection<AsteriskChannel> getChannels()
    {
        return channelManager.getChannels();
    }

    public AsteriskChannel getChannelByName(String name)
    {
        return channelManager.getChannelImplByName(name);
    }

    public AsteriskChannel getChannelById(String id)
    {
        return channelManager.getChannelImplById(id);
    }

    public Collection<AsteriskQueue> getQueues()
    {
        return queueManager.getQueues();
    }

    public String getVersion()
    {
        if (version == null)
        {
            ManagerResponse response;
            try
            {
                response = eventConnection.sendAction(new CommandAction(
                        "show version"));
                if (response instanceof CommandResponse)
                {
                    List result;

                    result = ((CommandResponse) response).getResult();
                    if (result.size() > 0)
                    {
                        version = (String) result.get(0);
                    }
                }
            }
            catch (Throwable e)
            {
                logger.warn("Unable to send 'show version' command.", e);
                return ""; // FIXME hack to fix NPE when not connected (throws IllegalStateException)
            }
        }

        return version;
    }

    public int[] getVersion(String file)
    {
        String fileVersion = null;
        String[] parts;
        int[] intParts;

        if (versions == null)
        {
            Map<String, String> map;
            ManagerResponse response;

            map = new HashMap<String, String>();
            try
            {
                response = eventConnection.sendAction(new CommandAction(
                        "show version files"));
                if (response instanceof CommandResponse)
                {
                    List<String> result;

                    result = ((CommandResponse) response).getResult();
                    for (int i = 2; i < result.size(); i++)
                    {
                        String line;
                        Matcher matcher;

                        line = (String) result.get(i);
                        matcher = SHOW_VERSION_FILES_PATTERN.matcher(line);
                        if (matcher.find())
                        {
                            String key = matcher.group(1);
                            String value = matcher.group(2);

                            map.put(key, value);
                        }
                    }

                    fileVersion = (String) map.get(file);
                    versions = map;
                }
            }
            catch (Exception e)
            {
                logger.warn("Unable to send 'show version files' command.", e);
            }
        }
        else
        {
            synchronized (versions)
            {
                fileVersion = versions.get(file);
            }
        }

        if (fileVersion == null)
        {
            return null;
        }

        parts = fileVersion.split("\\.");
        intParts = new int[parts.length];

        for (int i = 0; i < parts.length; i++)
        {
            try
            {
                intParts[i] = Integer.parseInt(parts[i]);
            }
            catch (NumberFormatException e)
            {
                intParts[i] = 0;
            }
        }

        return intParts;
    }

    /* Implementation of the ManagerEventListener interface */

    /**
     * Handles all events received from the Asterisk server.<br>
     * Events are queued until channels and queues are initialized and then
     * delegated to the dispatchEvent method.
     */
    public void onManagerEvent(ManagerEvent event)
    {
        if (event instanceof ConnectEvent)
        {
            handleConnectEvent((ConnectEvent) event);
        }
        else if (event instanceof DisconnectEvent)
        {
            handleDisconnectEvent((DisconnectEvent) event);
        }
        else if (event instanceof NewChannelEvent)
        {
            channelManager.handleNewChannelEvent((NewChannelEvent) event);
        }
        else if (event instanceof NewExtenEvent)
        {
            channelManager.handleNewExtenEvent((NewExtenEvent) event);
        }
        else if (event instanceof NewStateEvent)
        {
            channelManager.handleNewStateEvent((NewStateEvent) event);
        }
        else if (event instanceof NewCallerIdEvent)
        {
            channelManager.handleNewCallerIdEvent((NewCallerIdEvent) event);
        }
        else if (event instanceof LinkEvent)
        {
            channelManager.handleLinkEvent((LinkEvent) event);
        }
        else if (event instanceof UnlinkEvent)
        {
            channelManager.handleUnlinkEvent((UnlinkEvent) event);
        }
        else if (event instanceof RenameEvent)
        {
            channelManager.handleRenameEvent((RenameEvent) event);
        }
        else if (event instanceof HangupEvent)
        {
            channelManager.handleHangupEvent((HangupEvent) event);
        }
        else if (event instanceof JoinEvent)
        {
            queueManager.handleJoinEvent((JoinEvent) event);
        }
        else if (event instanceof LeaveEvent)
        {
            queueManager.handleLeaveEvent((LeaveEvent) event);
        }
    }

    /*
     * Resets the internal state when the connection to the asterisk server is
     * lost.
     */
    private void handleDisconnectEvent(DisconnectEvent disconnectEvent)
    {
        // reset version information as it might have changed while Asterisk
        // restarted
        version = null;
        versions = null;

        // same for channels and queues, they are reinitialized when reconnected
        channelManager.clear();
        queueManager.clear();
    }

    /*
     * Requests the current state from the asterisk server after the connection
     * to the asterisk server is restored.
     */
    private void handleConnectEvent(ConnectEvent connectEvent)
    {
        try
        {
            initializeChannels();
        }
        catch (Exception e)
        {
            logger.error("Unable to initialize channels after reconnect.", e);
        }

        try
        {
            initializeQueues();
        }
        catch (IOException e)
        {
            logger.error("Unable to initialize queues after reconnect.", e);
        }
    }
}
