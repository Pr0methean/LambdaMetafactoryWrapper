package io.github.pr0methean.invoke;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;

public interface LambdaMetafactoryCacheManager {
    <T> T wrapMethodHandle(final LambdaMetafactoryWrapper wrapper,
                           final MethodHandle implementation,
                           final LambdaMetafactoryWrapper.Parameters<T> parameters);

    <T> LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor getDescriptor(final LambdaMetafactoryWrapper wrapper,
                                                                             final Class<? super T> functionalInterface);

    MethodHandle getUnreflectedImplementation(final LambdaMetafactoryWrapper wrapper, final Executable implementation);

    <T> T wrap(final LambdaMetafactoryWrapper wrapper,
               final Executable implementation,
               final LambdaMetafactoryWrapper.Parameters<T> parameters);

    Object deserializeLambda(final SerializedLambda serializedLambda);

    void clearCaches();

    Executable findMethod(LambdaMetafactoryWrapper wrapper, LambdaMetafactoryWrapper.SerializedLambdaMethodDescription methodDescription);
}
