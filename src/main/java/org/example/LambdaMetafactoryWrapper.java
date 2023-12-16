package org.example;

import com.google.common.collect.MapMaker;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

public class LambdaMetafactoryWrapper {
    private record FunctionalInterfaceDescriptor(MethodType methodType, String methodName, MethodType nonCapturingReturning) {}

    // Combines WeakHashMap and IdentityHashMap functionality and is thread-safe
    private static final ConcurrentMap<Class<?>, FunctionalInterfaceDescriptor> DESCRIPTOR_MAP
            = new MapMaker().weakKeys().concurrencyLevel(1).makeMap();

    private static FunctionalInterfaceDescriptor describe(Class<?> samType) {
        List<Method> abstractMethods = Arrays.stream(samType.getMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toList();
        if (abstractMethods.size() != 1) {
            throw new IllegalArgumentException("Expected class " + samType + " to have 1 abstract method, but it has " +
                    abstractMethods.size());
        }
        Method abstractMethod = abstractMethods.getFirst();
        MethodType abstractMethodType = MethodType.methodType(abstractMethod.getReturnType(),
                abstractMethod.getParameterTypes());
        return new FunctionalInterfaceDescriptor(abstractMethodType, abstractMethod.getName(), MethodType.methodType(samType));
    }

    private final MethodHandles.Lookup lookup;

    public LambdaMetafactoryWrapper() {
        this(MethodHandles.lookup());
    }

    public LambdaMetafactoryWrapper(MethodHandles.Lookup lookup) {
        this.lookup = lookup;
    }

    @SuppressWarnings("unchecked")
    public <T> T wrap(Method implementation, Class<? super T> functionalInterface) {
        FunctionalInterfaceDescriptor descriptor
                = DESCRIPTOR_MAP.computeIfAbsent(functionalInterface, LambdaMetafactoryWrapper::describe);
        try {
            MethodHandle unreflectedImplementation = lookup.unreflect(implementation);
            return (T) LambdaMetafactory.metafactory(lookup, descriptor.methodName, descriptor.nonCapturingReturning,
                    descriptor.methodType, unreflectedImplementation, unreflectedImplementation.type()).getTarget().invoke();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
