package org.example;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;

public interface LambdaMetafactoryCacheManager {
    Object deserializeLambda(SerializedLambda serializedLambda);

    Executable findMethod(LambdaMetafactoryWrapper.SerializedLambdaMethodDescription methodDescription);

    @SuppressWarnings("unchecked")
    <T> T wrapMethodHandle(LambdaMetafactoryWrapper wrapper,
                           MethodHandle implementation, LambdaMetafactoryWrapper.Parameters<T> parameters);

    @SuppressWarnings("unchecked")
    <T> T wrap(LambdaMetafactoryWrapper wrapper, Executable implementation, LambdaMetafactoryWrapper.Parameters<T> parameters);

    MethodHandle getUnreflectedImplementation(LambdaMetafactoryWrapper wrapper, Executable implementation);

    <T> LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor getDescriptor(
            LambdaMetafactoryWrapper wrapper, Class<? super T> functionalInterface);
}
