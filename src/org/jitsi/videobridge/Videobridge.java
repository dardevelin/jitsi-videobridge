/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.lang.ref.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.osgi.framework.*;

/**
 * Represents the Jitsi Videobridge which creates, lists and destroys
 * {@link Conference} instances.
 *
 * @author Lyubomir Marinov
 */
public class Videobridge
{
    /**
     * The <tt>Logger</tt> used by the <tt>Videobridge</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(Videobridge.class);

    /**
     * The pseudo-random generator which is to be used when generating
     * {@link Conference} and {@link Channel} IDs in order to minimize busy
     * waiting for the value of {@link System#currentTimeMillis()} to change.
     */
    static final Random RANDOM = new Random();

    /**
     * Logs a specific <tt>String</tt> at debug level.
     *
     * @param s the <tt>String</tt> to log at debug level 
     */
    private static void logd(String s)
    {
        /*
         * FIXME Jitsi Videobridge uses the defaults of java.util.logging at the
         * time of this writing but wants to log at debug level at all times for
         * the time being in order to facilitate early development.
         */
        logger.info(s);
    }

    /**
     * The XML namespace of the <tt>TransportManager</tt> type to be initialized
     * by <tt>Channel</tt> by default.
     */
    private static String defaultTransportManager;

    /**
     * The <tt>ComponentImpl</tt> which has initialized this
     * <tt>Videobridge</tt>.
     */
    private final ComponentImpl component;

    /**
     * The <tt>Conference</tt>s of this <tt>Videobridge</tt> mapped by their
     * IDs.
     */
    private final Map<String, Conference> conferences
        = new HashMap<String, Conference>();

    /**
     * Initializes a new <tt>Videobridge</tt> instance which is initialized by a
     * specific <tt>ComponentImpl</tt>.
     *
     * @param component the <tt>ComponentImpl</tt> which initialized the new
     * instance
     */
    public Videobridge(ComponentImpl component)
    {
        if (component == null)
            throw new NullPointerException("component");

        this.component = component;

        logConfigurationServiceProperties();

        new ExpireThread(this).start();
    }

    /**
     * Initializes a new {@link Conference} instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt> and
     * adds the new instance to the list of existing <tt>Conference</tt>
     * instances. The new instance is owned by a specific conference focus i.e.
     * further/future requests to manage the new instance must come from the
     * specified <tt>focus</tt> or they will be ignored.
     *
     * @param focus a <tt>String</tt> which specifies the JID of the conference
     * focus which will own the new instance i.e. from whom further/future
     * requests to manage the new instance must come or they will be ignored 
     * @return a new <tt>Conference</tt> instance with an ID unique to the
     * <tt>Conference</tt> instances listed by this <tt>Videobridge</tt>
     */
    public Conference createConference(String focus)
    {
        Conference conference = null;

        do
        {
            String id = generateConferenceID();

            synchronized (conferences)
            {
                if (!conferences.containsKey(id))
                {
                    conference = new Conference(this, id, focus);
                    conferences.put(id, conference);

                    logd(
                            "Created conference " + id + ". The total number of"
                                + " conferences is now " + getConferenceCount()
                                + ".");
                }
            }
        }
        while (conference == null);

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
        boolean expireConference;

        synchronized (conferences)
        {
            if (conference.equals(conferences.get(id)))
            {
                conferences.remove(id);
                expireConference = true;
            }
            else
                expireConference = false;
        }
        if (expireConference)
            conference.expire();
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
     * Gets the <tt>ComponentImpl</tt> which has initialized this
     * <tt>Videobridge</tt>.
     *
     * @return the <tt>ComponentImpl</tt> which has initialized this
     * <tt>Videobridge</tt>
     */
    public ComponentImpl getComponent()
    {
        return component;
    }

    /**
     * Gets an existing {@link Conference} with a specific ID and a specific
     * conference focus.
     *
     * @param id the ID of the existing <tt>Conference</tt> to get
     * @param focus the JID of the conference focus of the existing
     * <tt>Conference</tt> to get. A <tt>Conference</tt> does not take orders
     * from a (remote) entity other than the conference focus who has
     * initialized it.
     * @return an existing <tt>Conference</tt> with the specified ID and the
     * specified conference focus or <tt>null</tt> if no <tt>Conference</tt>
     * with the specified ID and the specified conference focus is known to this
     * <tt>Videobridge</tt>
     */
    public Conference getConference(String id, String focus)
    {
        Conference conference;

        synchronized (conferences)
        {
            conference = conferences.get(id);
        }

        if (conference != null)
        {
            /*
             * A conference is owned by the focus who has initialized it and it
             * may be managed by that focus only.
             */
            if (conference.getFocus().equals(focus))
            {
                // It seems the conference is still active.
                conference.touch();
            }
            else
                conference = null;
        }

        return conference;
    }

    /**
     * Gets the number of <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the number of <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public int getConferenceCount()
    {
        synchronized (conferences)
        {
            return conferences.size();
        }
    }

    /**
     * Gets the <tt>Conference</tt>s of this <tt>Videobridge</tt>.
     *
     * @return the <tt>Conference</tt>s of this <tt>Videobridge</tt>
     */
    public Conference[] getConferences()
    {
        synchronized (conferences)
        {
            Collection<Conference> values = conferences.values();

            return values.toArray(new Conference[values.size()]);
        }
    }

    /**
     * Gets the XML namespace of the <tt>TransportManager</tt> type to be
     * initialized by <tt>Channel</tt> by default.
     *
     * @return the XML namespace of the <tt>TransportManager</tt> type to be
     * initialized by <tt>Channel</tt> by default
     */
    public String getDefaultTransportManager()
    {
        synchronized (Videobridge.class)
        {
            if (defaultTransportManager == null)
            {
                BundleContext bundleContext = getComponent().getBundleContext();

                if (bundleContext != null)
                {
                    ConfigurationService cfg
                        = ServiceUtils.getService(
                                bundleContext,
                                ConfigurationService.class);

                    if (cfg != null)
                    {
                        defaultTransportManager
                            = cfg.getString(
                                    Videobridge.class.getName()
                                        + ".defaultTransportManager");
                    }
                }
                if (!IceUdpTransportPacketExtension.NAMESPACE.equals(
                            defaultTransportManager)
                        && !RawUdpTransportPacketExtension.NAMESPACE.equals(
                                defaultTransportManager))
                {
                    defaultTransportManager
                        = IceUdpTransportPacketExtension.NAMESPACE;
                }
            }
            return defaultTransportManager;
        }
    }

    /**
     * Logs the properties of the <tt>ConfigurationService</tt> for the purposes
     * of debugging.
     */
    private void logConfigurationServiceProperties()
    {
        if (!logger.isInfoEnabled())
            return;

        boolean interrupted = false;

        try
        {
            BundleContext bundleContext = getComponent().getBundleContext();

            if (bundleContext != null)
            {
                ConfigurationService cfg
                    = ServiceUtils.getService(
                            bundleContext,
                            ConfigurationService.class);

                if (cfg != null)
                {
                    for (String p : cfg.getAllPropertyNames())
                    {
                        Object v = cfg.getProperty(p);

                        if (v != null)
                            logger.info(p + "=" + v);
                    }
                }
            }
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
                interrupted = true;
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }
        finally
        {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Implements a <tt>Thread</tt> which expires the {@link Channel}s of a
     * specific <tt>Videobridge</tt>.
     */
    private static class ExpireThread
        extends Thread
    {
        /**
         * The <tt>Videobridge</tt> which has its {@link Channel}s expired by
         * this instance. <tt>WeakReference</tt>d to allow this instance to
         * determine when it is to stop executing.
         */
        private final WeakReference<Videobridge> videobridge;

        /**
         * Initializes a new <tt>ExpireThread</tt> instance which is to expire
         * the {@link Channel}s of a specific <tt>Videobridge</tt>.
         *
         * @param videobridge the <tt>Videobridge</tt> which is to have its
         * <tt>Channel</tt>s expired by the new instance
         */
        public ExpireThread(Videobridge videobridge)
        {
            this.videobridge = new WeakReference<Videobridge>(videobridge);

            setDaemon(true);
            setName(getClass().getName());
        }

        /**
         * Expires the {@link Channel}s of a specific <tt>Videobridge</tt> if
         * they have been inactive for more than their advertised
         * <tt>expire</tt> number of seconds.
         *
         * @param videobridge the <tt>Videobridge</tt> which is to have its
         * <tt>Channel</tt>s expired if they have been inactive for more than
         * their advertised <tt>expire</tt> number of seconds
         */
        private void expire(Videobridge videobridge)
        {
            for (Conference conference : videobridge.getConferences())
            {
                /*
                 * The Conferences will live an iteration more than the
                 * Contents.
                 */
                Content[] contents = conference.getContents();

                if (contents.length == 0)
                {
                    if ((conference.getLastActivityTime()
                                + 1000L * Channel.DEFAULT_EXPIRE)
                            < System.currentTimeMillis())
                    {
                        try
                        {
                            conference.expire();
                        }
                        catch (Throwable t)
                        {
                            logger.warn(
                                    "Failed to expire conference "
                                        + conference.getID() + "!",
                                    t);
                            if (t instanceof ThreadDeath)
                                throw (ThreadDeath) t;
                        }
                    }
                }
                else
                {
                    for (Content content : conference.getContents())
                    {
                        /*
                         * The Contents will live an iteration more than the
                         * Channels.
                         */
                        Channel[] channels = content.getChannels();

                        if (channels.length == 0)
                        {
                            if ((content.getLastActivityTime()
                                        + 1000L * Channel.DEFAULT_EXPIRE)
                                    < System.currentTimeMillis())
                            {
                                try
                                {
                                    content.expire();
                                }
                                catch (Throwable t)
                                {
                                    logger.warn(
                                            "Failed to expire content "
                                                + content.getName()
                                                + " of conference "
                                                + conference.getID() + "!",
                                            t);
                                    if (t instanceof ThreadDeath)
                                        throw (ThreadDeath) t;
                                }
                            }
                        }
                        else
                        {
                            for (Channel channel : channels)
                            {
                                if ((channel.getLastActivityTime()
                                            + 1000L * channel.getExpire())
                                        < System.currentTimeMillis())
                                {
                                    try
                                    {
                                        channel.expire();
                                    }
                                    catch (Throwable t)
                                    {
                                        logger.warn(
                                                "Failed to expire channel "
                                                    + channel.getID()
                                                    + " of content "
                                                    + content.getName()
                                                    + " of conference "
                                                    + conference.getID() + "!",
                                                t);
                                        if (t instanceof ThreadDeath)
                                            throw (ThreadDeath) t;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * Runs the loop in the background which expires the {@link Channel}s of
         * {@link #videobridge} if they have been inactive for more than their
         * advertised <tt>expire</tt> number of seconds.
         */
        @Override
        public void run()
        {
            long wakeup = -1;
            final long sleep = Channel.DEFAULT_EXPIRE * 1000;

            do
            {
                /*
                 * If the Videobridge of this instance is not referenced
                 * anymore, then it is time for this Thread to stop executing.
                 */
                Videobridge videobridge = this.videobridge.get();

                if (videobridge == null)
                    break;

                /*
                 * Run the command of this Thread scheduled with a fixed delay.
                 */
                long now = System.currentTimeMillis();

                if (wakeup != -1)
                {
                    long slept = now - wakeup;

                    if (slept < sleep)
                    {
                        boolean interrupted = false;

                        try
                        {
                            Thread.sleep(sleep - slept);
                        }
                        catch (InterruptedException ie)
                        {
                            interrupted = true;
                        }
                        if (interrupted)
                            Thread.currentThread().interrupt();

                        continue;
                    }
                }

                wakeup = now;

                try
                {
                    expire(videobridge);
                }
                catch (Throwable t)
                {
                    logger.error(
                            "Failed to complete an iteration of automatic"
                                + " expiry of channels, contents, and"
                                + " conferences!",
                            t);
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }
            while (true);
        }
    }
}
