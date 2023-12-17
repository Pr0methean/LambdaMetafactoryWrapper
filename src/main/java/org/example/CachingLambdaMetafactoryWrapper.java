package org.example;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Executable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * To limit the number of weak references that the garbage collector needs to trace through, this class adheres to the
 * principle that if we know an object has the same lifespan as a {@link ClassLoader}, we should not hold it in a weak
 * reference, but can still hold the {@link ClassLoader} itself in one if it's capable of being unloaded. We also make
 * the simplifying assumption that {@link Executable} instances have the same lifespan as the declaring class, which
 * ensures that of the instances referring to the same constructor or method from the same declaring class, only one
 * will ever be reachable from a static root. This helps keep the old gen small and the card table mostly clean in
 * generational garbage collectors.
 */
public class CachingLambdaMetafactoryWrapper extends LambdaMetafactoryWrapper {

    private static final Set<ClassLoader> CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST;

    static {
        Set<ClassLoader> classLoadersThisClassCannotOutlast = Collections.newSetFromMap(new IdentityHashMap<>());
        classLoadersThisClassCannotOutlast.add(null); // for bootstrap CL
        classLoadersThisClassCannotOutlast.add(ClassLoader.getPlatformClassLoader());
        classLoadersThisClassCannotOutlast.add(ClassLoader.getSystemClassLoader());
        ClassLoader myLoader = CachingLambdaMetafactoryWrapper.class.getClassLoader();
        while (!classLoadersThisClassCannotOutlast.contains(myLoader)) {
            classLoadersThisClassCannotOutlast.add(myLoader);
            myLoader = myLoader.getParent();
        }
        CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST = classLoadersThisClassCannotOutlast;
    }

    private static class ClassLoaderSpecificCache {
        final ConcurrentHashMap<Class<?>, FunctionalInterfaceDescriptor> descriptors = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Executable, MethodHandle> unreflected = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Executable, Map<MetafactoryParameters<?>, Object>> cachedWrappers
                = new ConcurrentHashMap<>();

        Object computeWrapperIfAbsent(Executable implementation, MetafactoryParameters<?> parameters,
                          BiFunction<Executable, MetafactoryParameters<?>, Object> wrappingFunction) {
            return cachedWrappers.computeIfAbsent(implementation, impl -> newThreadSafeWeakKeyMap())
                    .computeIfAbsent(parameters, params -> wrappingFunction.apply(implementation, params));
        }
    }

    private static final Map<ClassLoader, ClassLoaderSpecificCache> CACHE_PER_UNLOADABLE_CLASSLOADER
            = newThreadSafeWeakKeyMap();
    private static final ClassLoaderSpecificCache CACHE_FOR_IMMORTAL_CLASSLOADERS = new ClassLoaderSpecificCache();
    private static final Map<Class<?>, FunctionalInterfaceDescriptor> ANON_AND_HIDDEN_DESCRIPTORS
            = newThreadSafeWeakKeyMap();
    private static final Map<Executable, MethodHandle> ANON_AND_HIDDEN_UNREFLECTED
            = newThreadSafeWeakKeyMap();
    private static final Map<Executable, Map<MetafactoryParameters<?>, Object>> ANON_AND_HIDDEN_WRAPPERS
            = newThreadSafeWeakKeyMap();
    private static final Map<MethodHandle, Map<MetafactoryParameters<?>, Object>> METHOD_HANDLE_WRAPPERS
            = newThreadSafeWeakKeyMap();

    public CachingLambdaMetafactoryWrapper() {
        super();
    }

    public CachingLambdaMetafactoryWrapper(MethodHandles.Lookup lookup) {
        super(lookup);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T wrapMethodHandle(MethodHandle implementation, MetafactoryParameters<T> parameters) {
        return (T) METHOD_HANDLE_WRAPPERS
                .computeIfAbsent(implementation, impl -> newThreadSafeWeakKeyMap())
                .computeIfAbsent(parameters, params -> wrapMethodHandleUncached(implementation, params));
    }

    private static ClassLoaderSpecificCache getClassLoaderSpecificCache(Class<?> clazz) {
        ClassLoader loader = clazz.getClassLoader();
        if (CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST.contains(loader)) {
            return CACHE_FOR_IMMORTAL_CLASSLOADERS;
        }
        return CACHE_PER_UNLOADABLE_CLASSLOADER.computeIfAbsent(loader, l -> new ClassLoaderSpecificCache());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T wrap(Executable implementation, MetafactoryParameters<T> parameters) {
        Class<?> declaringClass = implementation.getDeclaringClass();
        if (isNotReferencedByClassLoader(declaringClass)) {
            return (T) ANON_AND_HIDDEN_WRAPPERS
                    .computeIfAbsent(implementation, impl -> newThreadSafeWeakKeyMap())
                    .computeIfAbsent(parameters, params -> super.wrap(implementation, params));
        }
        return (T) getClassLoaderSpecificCache(declaringClass)
                .computeWrapperIfAbsent(implementation, parameters, super::wrap);
    }

    private static boolean isNotReferencedByClassLoader(Class<?> declaringClass) {
        return declaringClass.isAnonymousClass() || declaringClass.isHidden();
    }

    private static <K, V> Map<K, V> newThreadSafeWeakKeyMap() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    @Override
    protected <T> FunctionalInterfaceDescriptor getDescriptor(Class<? super T> functionalInterface) {
        if (isNotReferencedByClassLoader(functionalInterface)) {
            return ANON_AND_HIDDEN_DESCRIPTORS.computeIfAbsent(functionalInterface, super::getDescriptor);
        }
        return getClassLoaderSpecificCache(functionalInterface).descriptors
                .computeIfAbsent(functionalInterface, super::getDescriptor);
    }

    @Override
    protected MethodHandle getUnreflectedImplementation(Executable implementation) {
        Class<?> declaringClass = implementation.getDeclaringClass();
        if (isNotReferencedByClassLoader(declaringClass)) {
            return ANON_AND_HIDDEN_UNREFLECTED.computeIfAbsent(implementation, super::getUnreflectedImplementation);
        }
        return getClassLoaderSpecificCache(declaringClass)
                .unreflected.computeIfAbsent(implementation, super::getUnreflectedImplementation);
    }
}
