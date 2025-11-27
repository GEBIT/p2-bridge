/*
 * Copyright (c) 2007-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.sonatype.eclipse.bridge.internal;

import java.io.File;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.sonatype.eclipse.bridge.EclipseInstance;
import org.sonatype.eclipse.bridge.EclipseLocation;
import org.sonatype.eclipse.bridge.internal.util.EclipseStarterWithOwnClassLoader;

class DefaultEclipseInstance
    implements EclipseInstance
{
    private EclipseInstance state;
    
    private EclipseStarterWithOwnClassLoader eclipseStarter;

    private final EclipseLocation location;

    private final Lock eclipseLock;

    public DefaultEclipseInstance( final EclipseLocation location )
    {
        this.location = location;

        File[] runtimeJars = getEclipsePluginJars();
		try {
			this.eclipseStarter = new EclipseStarterWithOwnClassLoader(runtimeJars);
		} catch (Exception e) {
			// TODO
			throw new IllegalStateException(e);
		}

        state = new Stopped();
        eclipseLock = new ReentrantLock( true );
    }

    private File[] getEclipsePluginJars() {
    	File runtimeDir = new File(location.get(), "plugins");
    	File[] jars = runtimeDir.listFiles(new FilenameFilter() {
    	    @Override
    	    public boolean accept(File dir, String name) {
    	        return name.endsWith(".jar");
    	    }
    	});
		return jars;
	}

	@Override
    public <T> T getService( final Class<T> serviceType )
    {
        return state.getService( serviceType );
    }

    @Override
    public Long installBundle( final String location )
    {
        return state.installBundle( location );
    }

    @Override
    public Long installBundle( final String location, final InputStream inputStream )
    {
        return state.installBundle( location, inputStream );
    }

    @Override
    public void startBundle( final Long id )
    {
        state.startBundle( id );
    }

    @Override
    public EclipseInstance shutdown()
    {
        return state.shutdown();
    }

    @Override
    public EclipseInstance start( final Map<String, String> launchProperties )
    {
        return state.start( launchProperties );
    }

    @Override
    public EclipseInstance start( final Map<String, String> launchProperties, String[] args )
    {
        return state.start( launchProperties, args );
    }

    private class Started
        implements EclipseInstance
    {

        private final Map<WeakReference<?>, Object> activeServices;

        private final ReferenceQueue<Object> staleReferences;

        private final ReadWriteLock lock;

        private final Thread cleanupThread;

        public Started()
        {
            activeServices = new HashMap<WeakReference<?>, Object>();
            staleReferences = new ReferenceQueue<Object>();
            lock = new ReentrantReadWriteLock();
            cleanupThread = new Thread( new Cleanup(), "Stale Eclipse services cleanup" );
            cleanupThread.start();
        }

        @Override
        public <T> T getService( final Class<T> serviceType )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                final Object serviceReference = eclipseStarter.getServiceReference( serviceType );
                if ( serviceReference == null )
                {
                    throw new IllegalStateException( String.format( "There is no service available of type %s",
                        serviceType ) );
                }
                final T service = eclipseStarter.getService( serviceReference, serviceType );
                if ( service == null )
                {
                    throw new IllegalStateException( String.format( "There is no service available of type %s",
                        serviceType ) );
                }
                activeServices.put( new WeakReference<T>( service, staleReferences ), serviceReference );
                return service;
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public Long installBundle( final String location )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                return eclipseStarter.installBundle( location );
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public Long installBundle( final String location, final InputStream inputStream )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                return eclipseStarter.installBundle( location, inputStream );
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public void startBundle( final Long id )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                eclipseStarter.startBundle( id );
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public EclipseInstance shutdown()
        {
            try
            {
                lock.writeLock().lock();
                eclipseStarter.shutdown();
                eclipseLock.unlock();
                cleanupThread.interrupt();
                state = new Stopped();
                return state;
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.writeLock().unlock();
            }
        }

        @Override
        public EclipseInstance start( final Map<String, String> launchProperties )
        {
        	return start(launchProperties, new String[0]);
        }

        @Override
        public EclipseInstance start( final Map<String, String> launchProperties, String[] args )
        {
            return this;
        }

        private class Cleanup
            implements Runnable
        {

            @Override
            public void run()
            {
                try
                {
                    eclipseStarter.ungetService(activeServices.remove(staleReferences.remove()));
                }
                catch ( final Exception ignore )
                {
                    // we did our best
                }
            }

        }

    }

    private class Stopped
        implements EclipseInstance
    {

        @Override
        public <T> T getService( final Class<T> serviceType )
        {
            // TODO shall it fail since not started?
            return null;
        }

        @Override
        public Long installBundle( final String location )
        {
            // TODO shall it fail since not started?
            return null;
        }

        @Override
        public Long installBundle( final String location, final InputStream inputStream )
        {
            // TODO shall it fail since not started?
            return null;
        }

        @Override
        public void startBundle( final Long id )
        {
            // TODO shall it fail since not started?
        }

        @Override
        public EclipseInstance shutdown()
        {
            return this;
        }

        @Override
        public EclipseInstance start( final Map<String, String> launchProperties )
        {
        	return start(launchProperties, new String[0]);
        }

        @Override
        public EclipseInstance start( final Map<String, String> launchProperties, String[] args )
        {
            try
            {
                if ( !eclipseLock.tryLock() )
                {
                    throw new IllegalStateException( "Eclipse instance already in use" );
                }

                final String eclipseLocation = location.get().getAbsolutePath();

                final Map<String, String> properties = new HashMap<String, String>();
                if ( launchProperties != null )
                {
                    properties.putAll( launchProperties );
                }
                properties.put( "osgi.install.area", eclipseLocation );
                properties.put( "osgi.syspath", eclipseLocation + "/plugins" );
                properties.put( "osgi.parentClassloader", "fwk" );

                System.setProperty( "osgi.framework.useSystemProperties", "false" );

                // must backup the TCL as Equinox will set it to its own loader
                final ClassLoader tcl = Thread.currentThread().getContextClassLoader();
                try
                {
                	eclipseStarter.startup( args, properties );
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader( tcl );
                }

                state = new Started();

                return state;
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
        }
    }

}
