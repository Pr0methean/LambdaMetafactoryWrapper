package org.example;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static java.lang.invoke.LambdaMetafactory.FLAG_BRIDGES;
import static java.lang.invoke.LambdaMetafactory.FLAG_MARKERS;
import static java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE;
import static java.util.Objects.requireNonNull;

public class LambdaMetafactoryWrapper {
    public LambdaMetafactoryWrapper(MethodHandles.Lookup lookup) {
        this.lookup = lookup;
    }

    public LambdaMetafactoryWrapper() {
        this(MethodHandles.lookup());
    }

    protected final MethodHandles.Lookup lookup;

    public <T> T wrap(Executable implementation, MetafactoryParameters<T> parameters) {
        if (implementation instanceof Method && !parameters.capturedParameters.isEmpty()
                && !Modifier.isStatic(implementation.getModifiers())) {
            Class<?> receiverClass = parameters.capturedParameters.getFirst().getClass();
            if (!implementation.getDeclaringClass().isAssignableFrom(receiverClass)) {
                throw new IllegalArgumentException("First captured parameter for an instance method must be the "
                        + "receiver and must implement that method");
            }
        }
        MethodHandle unreflectedImplementation = getUnreflectedImplementation(implementation);
        return wrapMethodHandleUncached(unreflectedImplementation, parameters);
    }

    @SuppressWarnings("unchecked")
    protected final <T> T wrapMethodHandleUncached(MethodHandle implementation, MetafactoryParameters<T> parameters) {
        FunctionalInterfaceDescriptor descriptor = getDescriptor(parameters.functionalInterface);
        MethodType type = descriptor.nonCapturingReturning;
        if (!parameters.capturedParameters.isEmpty()) {
            type = type.appendParameterTypes(
                    parameters.capturedParameters.stream().map(Object::getClass).toArray(Class[]::new));
        }
        try {
            CallSite callSite;
            if (parameters.bridgeOverloadTypes.isEmpty() && parameters.markerInterfaces.isEmpty()
                    && !parameters.serializable) {
                callSite = LambdaMetafactory.metafactory(lookup, descriptor.methodName, type, descriptor.methodType,
                        implementation, implementation.type());
            } else {
                ArrayList<Object> additionalParameters = new ArrayList<>(4);
                additionalParameters.add(descriptor.methodType);
                additionalParameters.add(implementation);
                additionalParameters.add(implementation.type());
                additionalParameters.add(0);
                int flags = 0;
                if (parameters.serializable) {
                    flags |= FLAG_SERIALIZABLE;
                }
                if (!parameters.markerInterfaces.isEmpty()) {
                    flags |= FLAG_MARKERS;
                    additionalParameters.ensureCapacity(parameters.markerInterfaces.size() + 5);
                    additionalParameters.add(parameters.markerInterfaces.size());
                    additionalParameters.addAll(parameters.markerInterfaces);
                }
                if (!parameters.bridgeOverloadTypes.isEmpty()) {
                    flags |= FLAG_BRIDGES;
                    additionalParameters.ensureCapacity(additionalParameters.size()
                            + parameters.bridgeOverloadTypes.size() + 1);
                    additionalParameters.add(parameters.bridgeOverloadTypes.size());
                    additionalParameters.addAll(parameters.bridgeOverloadTypes);
                }
                additionalParameters.set(3, flags);
                callSite = LambdaMetafactory.altMetafactory(lookup, descriptor.methodName, type,
                        additionalParameters.toArray());
            }
            return (T) callSite.getTarget().invokeWithArguments(parameters.capturedParameters);
        } catch (Throwable t) {
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    public <T> T wrapMethodHandle(MethodHandle implementation, MetafactoryParameters<T> parameters) {
        return wrapMethodHandleUncached(implementation, parameters);
    }

    protected MethodHandle getUnreflectedImplementation(Executable implementation) {
        try {
            if (implementation instanceof Method) {
                return lookup.unreflect((Method) implementation);
            } else if (implementation instanceof Constructor<?>) {
                return lookup.unreflectConstructor((Constructor<?>) implementation);
            }
            throw new IllegalArgumentException(implementation + " is not a Constructor or Method");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected <T> FunctionalInterfaceDescriptor getDescriptor(Class<? super T> functionalInterface) {
        List<Method> abstractMethods = Arrays.stream(functionalInterface.getMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toList();
        if (abstractMethods.size() != 1) {
            throw new IllegalArgumentException("Expected class " + functionalInterface + " to have 1 abstract method, but it has " +
                    abstractMethods.size());
        }
        Method abstractMethod = abstractMethods.getFirst();
        MethodType abstractMethodType = MethodType.methodType(abstractMethod.getReturnType(),
                abstractMethod.getParameterTypes());
        return new FunctionalInterfaceDescriptor(abstractMethodType, abstractMethod.getName(), MethodType.methodType(functionalInterface));
    }

    public <T> T wrap(Executable implementation, Class<? super T> functionalInterface) {
        return wrap(implementation, MetafactoryParameters.<T>builder()
                .functionalInterface(functionalInterface)
                .build());
    }

    protected record FunctionalInterfaceDescriptor(
            MethodType methodType,
            String methodName,
            MethodType nonCapturingReturning) {
    }

    public record MetafactoryParameters<T>(
            Class<? super T> functionalInterface,
            boolean serializable,
            List<MethodType> bridgeOverloadTypes,
            List<Object> capturedParameters,
            List<Class<?>> markerInterfaces
    ) {
        public static <T> MetafactoryParametersBuilder<T> builder() {
            return new MetafactoryParametersBuilder<>();
        }

        public static class MetafactoryParametersBuilder<T> {
            // Defaults
            private boolean serializable = false;
            private final ArrayList<MethodType> bridgeOverloadTypes = new ArrayList<>();
            private final ArrayList<Object> capturedParameters = new ArrayList<>(); // may include nulls
            private final ArrayList<Class<?>> markerInterfaces = new ArrayList<>();
            private Class<? super T> functionalInterface;

            MetafactoryParametersBuilder() {
            }

            public MetafactoryParametersBuilder<T> functionalInterface(Class<? super T> functionalInterface) {
                this.functionalInterface = requireNonNull(functionalInterface);
                return this;
            }

            public MetafactoryParametersBuilder<T> serializable(boolean serializable) {
                this.serializable = serializable;
                return this;
            }

            @SuppressWarnings("UnusedReturnValue")
            public MetafactoryParametersBuilder<T> addMarkerInterface(Class<?> markerInterface) {
                markerInterfaces.add(requireNonNull(markerInterface));
                return this;
            }

            public MetafactoryParametersBuilder<T> addMarkerInterfaces(Collection<? extends Class<?>> markerInterfaces) {
                this.markerInterfaces.addAll(markerInterfaces);
                return this;
            }

            public MetafactoryParametersBuilder<T> clearMarkerInterfaces() {
                this.markerInterfaces.clear();
                return this;
            }

            public MetafactoryParametersBuilder<T> addBridgeOverload(MethodType bridgeOverloadType) {
                this.bridgeOverloadTypes.add(requireNonNull(bridgeOverloadType));
                return this;
            }

            public MetafactoryParametersBuilder<T> addBridgeOverloads(Collection<? extends MethodType> bridgeOverloadTypes) {
                this.bridgeOverloadTypes.addAll(bridgeOverloadTypes);
                return this;
            }

            public MetafactoryParametersBuilder<T> clearBridgeOverloads() {
                this.bridgeOverloadTypes.clear();
                return this;
            }

            public MetafactoryParametersBuilder<T> addCapturedParameter(Object capturedParameter) {
                capturedParameters.add(capturedParameter);
                return this;
            }

            public MetafactoryParametersBuilder<T> capturedParameters(Collection<?> capturedParameters) {
                this.capturedParameters.addAll(capturedParameters);
                return this;
            }

            public MetafactoryParametersBuilder<T> clearCapturedParameters() {
                this.capturedParameters.clear();
                return this;
            }

            public LambdaMetafactoryWrapper.MetafactoryParameters<T> build() {
                return new LambdaMetafactoryWrapper.MetafactoryParameters<>(
                        this.functionalInterface,
                        this.serializable,
                        this.bridgeOverloadTypes,
                        this.capturedParameters,
                        this.markerInterfaces);
            }
        }
    }
}
