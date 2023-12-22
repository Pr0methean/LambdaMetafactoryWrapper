package org.example;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class LambdaMetafactoryDefaultCacheManager implements LambdaMetafactoryCacheManager {
    private static final LambdaMetafactoryDefaultCacheManager INSTANCE = new LambdaMetafactoryDefaultCacheManager();
    // Don't want identity semantics
    private static final Map<SerializedLambda, Object> DESERIALIZATION_CACHE
            = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LambdaMetafactoryWrapper.SerializedLambdaMethodDescription, Executable> FIND_METHOD_CACHE
            = Collections.synchronizedMap(new WeakHashMap<>());
    public static LambdaMetafactoryDefaultCacheManager getInstance() {
        return INSTANCE;
    }
    private LambdaMetafactoryDefaultCacheManager() {}

    private static class ClassLoaderSpecificCache {
        final ConcurrentHashMap<Class<?>, LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor> descriptors = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Executable, MethodHandle> unreflected = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Executable, Map<LambdaMetafactoryWrapper.Parameters<?>, Object>> cachedWrappers
                = new ConcurrentHashMap<>();

        void clear() {
            descriptors.clear();
            unreflected.clear();
            cachedWrappers.clear();
        }
    }

    static <K, V> Map<K, V> newThreadSafeWeakKeyMap() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    private static boolean isReferencedByClassLoader(final Class<?> declaringClass) {
        return !declaringClass.isAnonymousClass() && !declaringClass.isHidden();
    }

    private static ClassLoaderSpecificCache getClassLoaderSpecificCache(final Class<?> clazz) {
        final ClassLoader loader = clazz.getClassLoader();
        if (CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST.contains(loader)) {
            return CACHE_FOR_IMMORTAL_CLASSLOADERS;
        }
        return CACHE_PER_UNLOADABLE_CLASSLOADER.computeIfAbsent(loader, l -> new ClassLoaderSpecificCache());
    }
    private static final Set<ClassLoader> CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST;
    private static final Map<ClassLoader, ClassLoaderSpecificCache> CACHE_PER_UNLOADABLE_CLASSLOADER
            = newThreadSafeWeakKeyMap();
    private static final ClassLoaderSpecificCache CACHE_FOR_IMMORTAL_CLASSLOADERS = new ClassLoaderSpecificCache();
    private static final Map<Class<?>, LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor> ANON_AND_HIDDEN_DESCRIPTORS
            = newThreadSafeWeakKeyMap();
    private static final Map<Executable, MethodHandle> ANON_AND_HIDDEN_UNREFLECTED
            = newThreadSafeWeakKeyMap();
    private static final Map<Executable, Map<LambdaMetafactoryWrapper.Parameters<?>, Object>> ANON_AND_HIDDEN_WRAPPERS
            = newThreadSafeWeakKeyMap();
    private static final Map<MethodHandle, Map<LambdaMetafactoryWrapper.Parameters<?>, Object>> METHOD_HANDLE_WRAPPERS
            = newThreadSafeWeakKeyMap();

    static {
        final Set<ClassLoader> classLoadersThisClassCannotOutlast = Collections.newSetFromMap(new IdentityHashMap<>(3));
        classLoadersThisClassCannotOutlast.add(null); // for bootstrap CL
        classLoadersThisClassCannotOutlast.add(ClassLoader.getPlatformClassLoader());
        classLoadersThisClassCannotOutlast.add(ClassLoader.getSystemClassLoader());
        if (isReferencedByClassLoader(LambdaMetafactoryDefaultCacheManager.class)) {
            ClassLoader myLoader = LambdaMetafactoryDefaultCacheManager.class.getClassLoader();
            while (!classLoadersThisClassCannotOutlast.contains(myLoader)) {
                classLoadersThisClassCannotOutlast.add(myLoader);
                myLoader = myLoader.getParent();
            }
        }
        CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST = classLoadersThisClassCannotOutlast;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T wrapMethodHandle(LambdaMetafactoryWrapper wrapper, MethodHandle implementation, LambdaMetafactoryWrapper.Parameters<T> parameters) {
        return (T) METHOD_HANDLE_WRAPPERS
                .computeIfAbsent(implementation, impl -> newThreadSafeWeakKeyMap())
                .computeIfAbsent(parameters, params -> wrapper.wrapMethodHandleUncached(implementation, params));
    }

    @Override
    public <T> LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor getDescriptor(LambdaMetafactoryWrapper wrapper, Class<? super T> functionalInterface) {
        if (!isReferencedByClassLoader(functionalInterface)) {
            return ANON_AND_HIDDEN_DESCRIPTORS.computeIfAbsent(functionalInterface, wrapper::getDescriptorUncached);
        }
        return getClassLoaderSpecificCache(functionalInterface).descriptors
                .computeIfAbsent(functionalInterface, wrapper::getDescriptorUncached);
    }

    @Override
    public MethodHandle getUnreflectedImplementation(LambdaMetafactoryWrapper wrapper, Executable implementation) {
        /*
        final Class<?> declaringClass = implementation.getDeclaringClass();
        if (!isReferencedByClassLoader(declaringClass)) {
            return ANON_AND_HIDDEN_UNREFLECTED.computeIfAbsent(implementation, wrapper::getUnreflectedImplementationUncached);
        }
        return getClassLoaderSpecificCache(declaringClass)
                .unreflected.computeIfAbsent(implementation, wrapper::getUnreflectedImplementationUncached);
         */
        return wrapper.getUnreflectedImplementationUncached(implementation);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T wrap(LambdaMetafactoryWrapper wrapper, Executable implementation, LambdaMetafactoryWrapper.Parameters<T> parameters) {
        final Class<?> declaringClass = implementation.getDeclaringClass();
        if (!isReferencedByClassLoader(declaringClass)) {
            return (T) ANON_AND_HIDDEN_WRAPPERS
                    .computeIfAbsent(implementation, impl -> newThreadSafeWeakKeyMap())
                    .computeIfAbsent(parameters, params -> wrapper.wrapUncached(implementation, params));
        }
        ClassLoaderSpecificCache classLoaderSpecificCache = getClassLoaderSpecificCache(declaringClass);
        return (T) classLoaderSpecificCache.cachedWrappers.computeIfAbsent(implementation, impl -> newThreadSafeWeakKeyMap())
                .computeIfAbsent(parameters, params -> wrapper.wrapUncached(implementation, params));
    }

    @Override
    public Object deserializeLambda(SerializedLambda serializedLambda) {
        return DESERIALIZATION_CACHE.computeIfAbsent(serializedLambda, LambdaMetafactoryWrapper::deserializeLambdaUncached);
    }

    @Override
    public void clearCaches() {
        CACHE_FOR_IMMORTAL_CLASSLOADERS.clear();
        CACHE_PER_UNLOADABLE_CLASSLOADER.clear();
        ANON_AND_HIDDEN_DESCRIPTORS.clear();
        ANON_AND_HIDDEN_UNREFLECTED.clear();
        ANON_AND_HIDDEN_WRAPPERS.clear();
        METHOD_HANDLE_WRAPPERS.clear();
        FIND_METHOD_CACHE.clear();
        DESERIALIZATION_CACHE.clear();
    }

    @Override
    public Executable findMethod(LambdaMetafactoryWrapper wrapper, LambdaMetafactoryWrapper.SerializedLambdaMethodDescription methodDescription) {
        return FIND_METHOD_CACHE.computeIfAbsent(methodDescription, wrapper::findMethodUncached);
    }
}
