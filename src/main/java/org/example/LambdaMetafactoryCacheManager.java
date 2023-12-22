package org.example;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.example.LambdaMetafactoryWrapper.Parameters;

import static org.example.LambdaMetafactoryWrapper.deserializeLambdaUncached;

public class LambdaMetafactoryCacheManager {
    private static final Map<SerializedLambda, Object> DESERIALIZATION_CACHE
            = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LambdaMetafactoryWrapper.SerializedLambdaMethodDescription, Executable> FIND_METHOD_CACHE
            = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MethodHandle, Map<Parameters<?>, Object>> METHOD_HANDLE_WRAPPERS
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();

    public Object deserializeLambda(SerializedLambda serializedLambda) {
        return DESERIALIZATION_CACHE.computeIfAbsent(serializedLambda, lambda -> {
            return deserializeLambdaUncached(lambda);
        });
    }

    public Executable findMethod(LambdaMetafactoryWrapper.SerializedLambdaMethodDescription methodDescription) {
        return FIND_METHOD_CACHE.computeIfAbsent(methodDescription, LambdaMetafactoryWrapper::findMethodUncached);
    }

    public <T> T wrapMethodHandle(final LambdaMetafactoryWrapper wrapper,
                                  final MethodHandle implementation, final Parameters<T> parameters) {
        return (T) METHOD_HANDLE_WRAPPERS
                .computeIfAbsent(implementation, impl -> LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap())
                .computeIfAbsent(parameters, params -> wrapper.wrapMethodHandleUncached(implementation, params));
    }
}
