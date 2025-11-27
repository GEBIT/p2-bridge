package org.sonatype.eclipse.bridge.internal.util;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/**
 * Launcher that starts an isolated Equinox runtime loaded from provided jars.
 * It intentionally prevents host class loader delegation for
 * org.osgi/org.eclipse.* packages, forcing the Equinox loader to provide those
 * classes.
 */
public final class EclipseStarterWithOwnClassLoader implements Closeable {

	private final URLClassLoader runtimeCL;
	private final ClassLoaderBridge classLoaderBridge;
	
	private final Class<?> eclipseStarterClass;
	private final Method setInitialProperties;
	private final Method startup;
	private final Method shutdown;
	private final Method getSystemBundleContext;
	
	private final Class<?> bundleContextClass;
	private final Method getServiceReference;
	private final Method getService;
	private final Method ungetService;
	private final Method installBundle;
	private final Method installBundle2;
	private final Method getBundle;
	
	private final Class<?> bundleClass;
	private final Method getBundleId;
	private final Method start;
	
	private final Class<?> serviceReferenceClass;

	/**
	 * Parent that delegates to host except for blocking org.osgi/org.eclipse
	 * packages.
	 */
	private static final class BlockingParent extends ClassLoader {
		private final ClassLoader host;

		BlockingParent(ClassLoader host) {
			super(null);
			this.host = host;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			// Block these packages so Equinox loads the OSGi/Equinox classes itself.
			if (name.startsWith("org.osgi.") || name.startsWith("org.eclipse.")
					|| name.startsWith("org.apache.felix.")) {
				throw new ClassNotFoundException(name);
			}
			return host.loadClass(name);
		}
	}

	/**
	 * @param runtimeJars list of jar files that form Eclipse runtime (equiv to
	 *                    folder with org.eclipse.osgi_*.jar etc)
	 */
	public EclipseStarterWithOwnClassLoader(File[] runtimeJars) throws Exception {
		URL[] urls = new URL[runtimeJars.length];
		for (int i = 0; i < runtimeJars.length; i++) {
			try {
				urls[i] = runtimeJars[i].toURI().toURL();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		this.runtimeCL = new URLClassLoader(urls, new BlockingParent(this.getClass().getClassLoader()));

		this.eclipseStarterClass = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter", true, runtimeCL);
		this.setInitialProperties = eclipseStarterClass.getMethod("setInitialProperties", Map.class);
		this.startup = eclipseStarterClass.getMethod("startup", String[].class, Runnable.class);
		this.shutdown = eclipseStarterClass.getMethod("shutdown");
		this.getSystemBundleContext = eclipseStarterClass.getMethod("getSystemBundleContext");

		this.bundleContextClass = Class.forName("org.osgi.framework.BundleContext", true, runtimeCL);
		this.serviceReferenceClass = Class.forName("org.osgi.framework.ServiceReference", true, runtimeCL);
		this.getServiceReference = bundleContextClass.getMethod("getServiceReference", String.class);
		this.getService = bundleContextClass.getMethod("getService", serviceReferenceClass);
		this.ungetService = bundleContextClass.getMethod("ungetService", serviceReferenceClass);
		this.installBundle = bundleContextClass.getMethod("installBundle", String.class);
		this.installBundle2 = bundleContextClass.getMethod("installBundle", String.class, InputStream.class);
		this.getBundle = bundleContextClass.getMethod("getBundle", long.class);
		
		this.bundleClass = Class.forName("org.osgi.framework.Bundle", true, runtimeCL);
		this.getBundleId = bundleClass.getMethod("getBundleId");
		this.start = bundleClass.getMethod("start");
		
		this.classLoaderBridge = new ClassLoaderBridge(runtimeCL);
	}

	/**
	 * Start Equinox with the given OSGi properties map.
	 */
	public void startup(String[] args, Map<String, String> properties) throws Exception {
		// start with eqLoader as TCCL
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			// set initial properties
			setInitialProperties.invoke(null, properties);
			startup.invoke(null, new Object[] { args, null });
		} finally {
			t.setContextClassLoader(prev);
		}
	}

	private Object getSystemBundleContext() {
		try {
			return getSystemBundleContext.invoke(null);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		}
	}

	public void shutdown() throws Exception {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			shutdown.invoke(null);
		} finally {
			t.setContextClassLoader(prev);
		}
	}

	@Override
	public void close() {
		try {
			shutdown();
		} catch (Throwable ignore) {
		}
		try {
			runtimeCL.close();
		} catch (Throwable ignore) {
		}
	}

	/** Expose eqLoader for reflective Class.forName with eqLoader when needed. */
	public ClassLoader getEquinoxClassLoader() {
		return runtimeCL;
	}

	public Object getServiceReference(Class<?> serviceType) {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			return getServiceReference.invoke(getSystemBundleContext(), serviceType.getName());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} finally {
			t.setContextClassLoader(prev);
		}
	}

	public <T> T getService(Object serviceReference, Class<T> serviceType) {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			Object service = getService.invoke(getSystemBundleContext(), serviceReference);
			return classLoaderBridge.toHostObject(service, serviceType);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} finally {
			t.setContextClassLoader(prev);
		}
	}
	
	public Long installBundle(final String location) {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			Object bundle = installBundle.invoke(getSystemBundleContext(), location);
			return (Long) getBundleId.invoke(bundle);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} finally {
			t.setContextClassLoader(prev);
		}
	}
	
	public Long installBundle(final String location, final InputStream inputStream) {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			Object bundle = installBundle2.invoke(getSystemBundleContext(), location, inputStream);
			return (Long) getBundleId.invoke(bundle);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} finally {
			t.setContextClassLoader(prev);
		}
	}
	
	public void startBundle(final Long id) {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			Object bundle = getBundle.invoke(getSystemBundleContext(), id);
			start.invoke(bundle);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} finally {
			t.setContextClassLoader(prev);
		}
	}

	public void ungetService(Object serviceReference) {
		final Thread t = Thread.currentThread();
		final ClassLoader prev = t.getContextClassLoader();
		try {
			t.setContextClassLoader(runtimeCL);
			ungetService.invoke(getSystemBundleContext(), serviceReference);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e);
		} finally {
			t.setContextClassLoader(prev);
		}
	}
}