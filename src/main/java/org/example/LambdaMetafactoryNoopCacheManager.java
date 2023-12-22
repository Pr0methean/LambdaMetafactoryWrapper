package org.example;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;

public class LambdaMetafactoryNoopCacheManager implements LambdaMetafactoryCacheManager {
    @Override
    public <T> T wrapMethodHandle(LambdaMetafactoryWrapper wrapper, MethodHandle implementation, LambdaMetafactoryWrapper.Parameters<T> parameters) {
        return wrapper.wrapMethodHandleUncached(implementation, parameters);
    }

    @Override
    public <T> LambdaMetafactoryWrapper.FunctionalInterfaceDescriptor getDescriptor(LambdaMetafactoryWrapper wrapper, Class<? super T> functionalInterface) {
        return wrapper.getDescriptorUncached(functionalInterface);
    }

    @Override
    public MethodHandle getUnreflectedImplementation(LambdaMetafactoryWrapper wrapper, Executable implementation) {
        return wrapper.getUnreflectedImplementationUncached(implementation);
    }

    @Override
    public <T> T wrap(LambdaMetafactoryWrapper wrapper, Executable implementation, LambdaMetafactoryWrapper.Parameters<T> parameters) {
        return wrapper.wrapUncached(implementation, parameters);
    }

    @Override
    public Object deserializeLambda(SerializedLambda serializedLambda) {
        return LambdaMetafactoryWrapper.deserializeLambdaUncached(serializedLambda);
    }

    @Override
    public void clearCaches() {
        // No-op.
    }

    @Override
    public Executable findMethod(LambdaMetafactoryWrapper wrapper, LambdaMetafactoryWrapper.SerializedLambdaMethodDescription methodDescription) {
        return LambdaMetafactoryWrapper.findMethodUncached(methodDescription);
    }
}
