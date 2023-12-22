package org.example;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.example.LambdaMetafactoryWrapper.Parameters;

public class LambdaMetafactoryDefaultCacheManager implements LambdaMetafactoryCacheManager {
    private LambdaMetafactoryDefaultCacheManager() {}
    private static final LambdaMetafactoryDefaultCacheManager INSTANCE = new LambdaMetafactoryDefaultCacheManager();
    public static LambdaMetafactoryDefaultCacheManager getInstance() {
        return INSTANCE;
    }
    private static final Map<SerializedLambda, Object> DESERIALIZATION_CACHE
            = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LambdaMetafactoryWrapper.SerializedLambdaMethodDescription, Executable> FIND_METHOD_CACHE
            = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MethodHandle, Map<Parameters<?>, Object>> METHOD_HANDLE_WRAPPERS
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();
    private static final Map<Executable, MethodHandle> UNREFLECTED
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();
    private static final Map<Executable, Map<Parameters<?>, Object>> WRAPPERS
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();
    private static final Map<Class<?>, LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor> DESCRIPTORS
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();

    @Override
    public Object deserializeLambda(SerializedLambda serializedLambda) {
        return DESERIALIZATION_CACHE.computeIfAbsent(serializedLambda, LambdaMetafactoryWrapper::deserializeLambdaUncached);
    }

    @Override
    public Executable findMethod(LambdaMetafactoryWrapper.SerializedLambdaMethodDescription methodDescription) {
        return FIND_METHOD_CACHE.computeIfAbsent(methodDescription, LambdaMetafactoryWrapper::findMethodUncached);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T wrapMethodHandle(final LambdaMetafactoryWrapper wrapper,
                                  final MethodHandle implementation, final Parameters<T> parameters) {
        return (T) METHOD_HANDLE_WRAPPERS
                .computeIfAbsent(implementation, impl -> LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap())
                .computeIfAbsent(parameters, params -> wrapper.wrapMethodHandleUncached(implementation, params));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T wrap(LambdaMetafactoryWrapper wrapper, Executable implementation, Parameters<T> parameters) {
        return (T) WRAPPERS.computeIfAbsent(implementation, impl -> new ConcurrentHashMap<>())
                .computeIfAbsent(parameters, params -> wrapper.wrapUncached(implementation, params));
    }

    @Override
    public MethodHandle getUnreflectedImplementation(LambdaMetafactoryWrapper wrapper, final Executable implementation) {
        return UNREFLECTED.computeIfAbsent(implementation, wrapper::getUnreflectedImplementationUncached);
    }

    @Override
    public <T> LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor getDescriptor(
            LambdaMetafactoryWrapper wrapper, final Class<? super T> functionalInterface) {
        return DESCRIPTORS.computeIfAbsent(functionalInterface, wrapper::getDescriptorUncached);
    }
}
