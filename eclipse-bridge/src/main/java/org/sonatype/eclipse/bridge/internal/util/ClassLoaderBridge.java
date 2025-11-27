package org.sonatype.eclipse.bridge.internal.util;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public final class ClassLoaderBridge {

	private final ClassLoader runtimeCL;

	private final ConcurrentMap<MethodKey, Method> methodCache = new ConcurrentHashMap<>();

	public ClassLoaderBridge(ClassLoader runtimeCL) {
		this.runtimeCL = Objects.requireNonNull(runtimeCL);
	}

	public <T> T toHostObject(Object runtimeObj, Class<T> hostType) {
		return bridge(runtimeObj, hostType, runtimeCL);
	}

	@SuppressWarnings("unchecked")
	private <T> T bridge(Object obj, Class<T> targetType, ClassLoader objectCL) {
		if (obj == null)
			return null;
		// Direct assignable — no proxy needed
		if (targetType.isInstance(obj)) {
			return (T) obj;
		}
		return (T) Proxy.newProxyInstance(targetType.getClassLoader(), new Class<?>[] { targetType },
				new ProxyHandler(obj, objectCL, targetType.getClassLoader()));
	}

	private final class ProxyHandler implements InvocationHandler {

		private final Object obj;
		private final Class<?> objectClass;
		private final ClassLoader objectCL;
		private final ClassLoader callerCL;

		ProxyHandler(Object obj, ClassLoader objectCL, ClassLoader callerCL) {
			this.obj = obj;
			this.objectClass = obj.getClass();
			this.objectCL = objectCL;
			this.callerCL = callerCL;
		}

		@Override
		public Object invoke(Object proxy, Method calledMethod, Object[] passedArgs) throws Throwable {
			Method objectMethod = getOrResolveMethod(calledMethod);
			Object[] objectMethodArgs = convertArgs(passedArgs, objectMethod.getParameterTypes());
			try {
				Object result = objectMethod.invoke(obj, objectMethodArgs);
				return convertReturnValue(result, calledMethod.getReturnType());
			} catch (InvocationTargetException e) {
				throw unwrap(e.getCause());
			}
		}

		private Method getOrResolveMethod(Method calledMethod) throws NoSuchMethodException, ClassNotFoundException {
			MethodKey key = new MethodKey(objectClass, calledMethod);
			Method cached = methodCache.get(key);
			if (cached != null)
				return cached;
			// resolve parameter types
			Class<?>[] calledMethodParamTypes = calledMethod.getParameterTypes();
			Class<?>[] targetMethodParamTypes = new Class<?>[calledMethodParamTypes.length];
			for (int i = 0; i < calledMethodParamTypes.length; i++) {
				targetMethodParamTypes[i] = convertClass(calledMethodParamTypes[i]);
			}
			Method resolvedMethod = objectClass.getMethod(calledMethod.getName(), targetMethodParamTypes);
			methodCache.put(key, resolvedMethod);
			return resolvedMethod;
		}

		private Class<?> convertClass(Class<?> c) throws ClassNotFoundException {
			if (c.isPrimitive())
				return c;
			if (c.isArray()) {
				Class<?> comp = convertClass(c.getComponentType());
				return Array.newInstance(comp, 0).getClass();
			}
			return Class.forName(c.getName(), false, objectCL);
		}

		private Object[] convertArgs(Object[] passedArgs, Class<?>[] objectMethodParameterTypes) throws Exception {
			if (passedArgs == null || passedArgs.length == 0)
				return passedArgs;
			Object[] result = new Object[passedArgs.length];
			for (int i = 0; i < passedArgs.length; i++) {
				result[i] = convertValue(passedArgs[i], callerCL, objectMethodParameterTypes[i], objectCL);
			}
			return result;
		}

		private Object convertReturnValue(Object returnValue, Class<?> calledMethodReturnType) throws Exception {
			return convertValue(returnValue, objectCL, calledMethodReturnType, callerCL);
		}

		private Object convertValue(Object value, ClassLoader valueClassLoader, Class<?> targetType,
				ClassLoader targetClassLoader) throws Exception {
			if (value == null)
				return null;
			if (value.getClass().isPrimitive())
				return value;
			if (value.getClass().isArray()) {
				int len = Array.getLength(value);
				Class<?> componentType = targetType.getComponentType();
				Object arr = Array.newInstance(componentType, len);
				for (int i = 0; i < len; i++) {
					Array.set(arr, i,
							convertValue(Array.get(value, i), valueClassLoader, componentType, targetClassLoader));
				}
				return arr;
			}
			return bridge(value, targetType, valueClassLoader);
		}

		private Throwable unwrap(Throwable t) {
			if (t instanceof InvocationTargetException) {
				return unwrap(((InvocationTargetException) t).getTargetException());
			}
			return t;
		}
	}

	private static final class MethodKey {
		private final Class<?> targetClass;
		private final String methodName;
		private final List<Class<?>> paramTypes;

		MethodKey(Class<?> targetClass, Method m) {
			this.targetClass = targetClass;
			this.methodName = m.getName();
			this.paramTypes = Arrays.asList(m.getParameterTypes());
		}

		@Override
		public int hashCode() {
			return Objects.hash(targetClass, methodName, paramTypes);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof MethodKey))
				return false;
			MethodKey k = (MethodKey) o;
			return targetClass.equals(k.targetClass) && methodName.equals(k.methodName)
					&& paramTypes.equals(k.paramTypes);
		}
	}
}