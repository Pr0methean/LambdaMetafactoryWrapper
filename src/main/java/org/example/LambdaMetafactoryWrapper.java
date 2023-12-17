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
import java.util.Objects;
import java.util.logging.Logger;

import static java.lang.invoke.LambdaMetafactory.FLAG_BRIDGES;
import static java.lang.invoke.LambdaMetafactory.FLAG_MARKERS;
import static java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE;
import static java.util.Objects.requireNonNull;

public class LambdaMetafactoryWrapper {
    @SuppressWarnings("unused")
    private static final Logger LOG = Logger.getLogger(LambdaMetafactoryWrapper.class.getSimpleName());

    public LambdaMetafactoryWrapper(MethodHandles.Lookup lookup) {
        this.lookup = lookup;
    }

    public LambdaMetafactoryWrapper() {
        this(MethodHandles.lookup());
    }

    protected final MethodHandles.Lookup lookup;

    public <T> T wrap(Executable implementation, Parameters<T> parameters) {
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
    protected final <T> T wrapMethodHandleUncached(MethodHandle implementation, Parameters<T> parameters) {
        FunctionalInterfaceDescriptor descriptor = getDescriptor(parameters.functionalInterface);
        Class<?>[] capturedTypes = parameters.capturedParameters.stream().map(Object::getClass).toArray(Class[]::new);
        MethodType implementationType = implementation.type().dropParameterTypes(0,
                parameters.capturedParameters.size());
        for (int i = 0; i < parameters.capturedParameters.size(); i++) {
            Class<?> implType = implementation.type().parameterType(i);
            if (implType.isPrimitive()) {
                capturedTypes[i] = implType;
            }
        }
        MethodType factoryType = MethodType.methodType(parameters.functionalInterface, capturedTypes);
        MethodType descriptorType = descriptor.methodType;
        try {
            CallSite callSite;
            if (parameters.bridgeOverloadTypes.isEmpty() && parameters.markerInterfaces.isEmpty()
                    && !parameters.serializable) {
                callSite = LambdaMetafactory.metafactory(lookup, descriptor.methodName, factoryType,
                        descriptorType, implementation, implementationType);
            } else {
                ArrayList<Object> additionalParameters = new ArrayList<>(4);
                additionalParameters.add(descriptorType);
                additionalParameters.add(implementation);
                additionalParameters.add(implementationType);
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
                callSite = LambdaMetafactory.altMetafactory(lookup, descriptor.methodName, factoryType,
                        additionalParameters.toArray());
            }
            return (T) callSite.getTarget().invokeWithArguments(parameters.capturedParameters);
        } catch (Throwable t) {
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    public <T> T wrapMethodHandle(MethodHandle implementation, Parameters<T> parameters) {
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
        MethodType abstractMethodType = null;
        try {
            abstractMethodType = lookup.unreflect(abstractMethod).type();
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return new FunctionalInterfaceDescriptor(abstractMethodType.dropParameterTypes(0, 1), abstractMethod.getName(),
                MethodType.methodType(functionalInterface));
    }

    public <T> T wrap(Executable implementation, Class<? super T> functionalInterface) {
        return wrap(implementation, Parameters.<T>builder()
                .functionalInterface(functionalInterface)
                .build());
    }

    private static class DefaultInstanceLazyLoader {
        static LambdaMetafactoryWrapper DEFAULT_INSTANCE = new LambdaMetafactoryWrapper(MethodHandles.publicLookup());
    }

    public static LambdaMetafactoryWrapper getDefaultInstance() {
        return DefaultInstanceLazyLoader.DEFAULT_INSTANCE;
    }

    protected record FunctionalInterfaceDescriptor(
            MethodType methodType,
            String methodName,
            MethodType nonCapturingReturning) {
    }

    public record Parameters<T>(
            Class<? super T> functionalInterface,
            boolean serializable,
            List<MethodType> bridgeOverloadTypes,
            List<Object> capturedParameters,
            List<Class<?>> markerInterfaces
    ) {
        public static <T> Builder<T> builder() {
            return new Builder<>();
        }

        public static class Builder<T> {
            // Defaults
            private boolean serializable = false;
            private final ArrayList<MethodType> bridgeOverloadTypes = new ArrayList<>();
            private final ArrayList<Object> capturedParameters = new ArrayList<>(); // may include nulls
            private final ArrayList<Class<?>> markerInterfaces = new ArrayList<>();
            private Class<? super T> functionalInterface;

            Builder() {
            }

            public Builder<T> functionalInterface(Class<? super T> functionalInterface) {
                this.functionalInterface = requireNonNull(functionalInterface);
                return this;
            }

            public Builder<T> serializable(boolean serializable) {
                this.serializable = serializable;
                return this;
            }

            @SuppressWarnings("UnusedReturnValue")
            public Builder<T> addMarkerInterface(Class<?> markerInterface) {
                markerInterfaces.add(requireNonNull(markerInterface));
                return this;
            }

            public Builder<T> addMarkerInterfaces(Collection<? extends Class<?>> markerInterfaces) {
                this.markerInterfaces.addAll(markerInterfaces);
                return this;
            }

            public Builder<T> clearMarkerInterfaces() {
                this.markerInterfaces.clear();
                return this;
            }

            public Builder<T> addBridgeOverload(MethodType bridgeOverloadType) {
                this.bridgeOverloadTypes.add(requireNonNull(bridgeOverloadType));
                return this;
            }

            public Builder<T> addBridgeOverloads(Collection<? extends MethodType> bridgeOverloadTypes) {
                this.bridgeOverloadTypes.addAll(bridgeOverloadTypes);
                return this;
            }

            public Builder<T> clearBridgeOverloads() {
                this.bridgeOverloadTypes.clear();
                return this;
            }

            public Builder<T> addCapturedParameter(Object capturedParameter) {
                capturedParameters.add(capturedParameter);
                return this;
            }

            public Builder<T> capturedParameters(Collection<?> capturedParameters) {
                this.capturedParameters.addAll(capturedParameters);
                return this;
            }

            public Builder<T> clearCapturedParameters() {
                this.capturedParameters.clear();
                return this;
            }

            public Parameters<T> build() {
                return new Parameters<>(
                        this.functionalInterface,
                        this.serializable,
                        this.bridgeOverloadTypes,
                        this.capturedParameters,
                        this.markerInterfaces);
            }
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(lookup, getClass());
    }

    @Override
    public boolean equals(Object obj) {
        return (obj == this) || obj != null &&
                (obj.getClass() == getClass() && lookup == ((LambdaMetafactoryWrapper)obj).lookup);
    }
}
