package org.example;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import static java.lang.invoke.LambdaMetafactory.FLAG_BRIDGES;
import static java.lang.invoke.LambdaMetafactory.FLAG_MARKERS;
import static java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE;
import static java.util.Objects.requireNonNull;

public class LambdaMetafactoryWrapper {
    @SuppressWarnings("unused")
    private static final Logger LOG = Logger.getLogger(LambdaMetafactoryWrapper.class.getSimpleName());
    private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];
    private static final Set<ClassLoader> CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST;
    private static final Map<ClassLoader, ClassLoaderSpecificCache> CACHE_PER_UNLOADABLE_CLASSLOADER
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();
    private static final ClassLoaderSpecificCache CACHE_FOR_IMMORTAL_CLASSLOADERS = new ClassLoaderSpecificCache();
    private static final Map<Class<?>, FunctionalInterfaceDescriptor> ANON_AND_HIDDEN_DESCRIPTORS
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();
    private static final Map<Executable, MethodHandle> ANON_AND_HIDDEN_UNREFLECTED
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();
    private static final Map<Executable, Map<Parameters<?>, Object>> ANON_AND_HIDDEN_WRAPPERS
            = LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap();

    static {
        final Set<ClassLoader> classLoadersThisClassCannotOutlast = Collections.newSetFromMap(new IdentityHashMap<>(3));
        classLoadersThisClassCannotOutlast.add(null); // for bootstrap CL
        classLoadersThisClassCannotOutlast.add(ClassLoader.getPlatformClassLoader());
        classLoadersThisClassCannotOutlast.add(ClassLoader.getSystemClassLoader());
        if (isReferencedByClassLoader(LambdaMetafactoryWrapper.class)) {
            ClassLoader myLoader = LambdaMetafactoryWrapper.class.getClassLoader();
            while (!classLoadersThisClassCannotOutlast.contains(myLoader)) {
                classLoadersThisClassCannotOutlast.add(myLoader);
                myLoader = myLoader.getParent();
            }
        }
        CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST = classLoadersThisClassCannotOutlast;
    }

    private final MethodHandles.Lookup serialLookup;
    private final LambdaMetafactoryCacheManager cacheManager = new LambdaMetafactoryCacheManager();

    public LambdaMetafactoryWrapper(final MethodHandles.Lookup lookup) {
        this.lookup = lookup;
        try {
            this.serialLookup = MethodHandles.privateLookupIn(LambdaMetafactoryWrapper.class, lookup);
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public LambdaMetafactoryWrapper() {
        this(MethodHandles.lookup());
    }

    protected final MethodHandles.Lookup lookup;

    @SuppressWarnings("unchecked")
    public <T> T wrapMethodHandle(final MethodHandle implementation, final Parameters<T> parameters) {
        return cacheManager.wrapMethodHandle(this, implementation, parameters);
    }

    private static ClassLoaderSpecificCache getClassLoaderSpecificCache(final Class<?> clazz) {
        final ClassLoader loader = clazz.getClassLoader();
        if (CLASS_LOADERS_THIS_CLASS_CANNOT_OUTLAST.contains(loader)) {
            return CACHE_FOR_IMMORTAL_CLASSLOADERS;
        }
        return CACHE_PER_UNLOADABLE_CLASSLOADER.computeIfAbsent(loader, l -> new ClassLoaderSpecificCache());
    }

    protected <T> T wrapUncached(final Executable implementation, final Parameters<T> parameters) {
        if (implementation instanceof Method && !parameters.capturedParameters.isEmpty()
                && !Modifier.isStatic(implementation.getModifiers())) {
            final Class<?> receiverClass = parameters.capturedParameters.getFirst().getClass();
            if (!implementation.getDeclaringClass().isAssignableFrom(receiverClass)) {
                throw new IllegalArgumentException("First captured parameter for an instance method must be the "
                        + "receiver and must implement that method");
            }
        }
        final MethodHandle unreflectedImplementation = getUnreflectedImplementation(implementation);
        return wrapMethodHandleUncached(unreflectedImplementation, parameters);
    }

    @SuppressWarnings("unchecked")
    protected final <T> T wrapMethodHandleUncached(final MethodHandle implementation, final Parameters<T> parameters) {
        final FunctionalInterfaceDescriptor descriptor = getDescriptor(parameters.functionalInterface);
        final MethodType implType = implementation.type();
        final int implParamCount = implType.parameterCount();
        List<Object> capturedParameters = parameters.capturedParameters;
        final int capturedParamCount = capturedParameters.size();
        final Class<?>[] capturedTypes;
        final MethodType invocationType;
        if (implParamCount > 0) {
            final Class<?> lastImplParamType = implType.parameterType(implParamCount - 1);
            if (lastImplParamType.isArray()
                    && (capturedParameters.isEmpty()
                    || !capturedParameters.getLast().getClass().isArray())
            ) {
                final int varargsLength = capturedParamCount - implParamCount + 1;
                final Object varargs = Array.newInstance(lastImplParamType.componentType(), varargsLength);
                for (int i = 0; i < varargsLength; i++) {
                    Array.set(varargs, i, capturedParameters.get(i + implParamCount - 1));
                }
                capturedParameters = new ArrayList<>(capturedParameters.subList(0, implParamCount));
                capturedParameters.set(implParamCount - 1, varargs);
            }
            capturedTypes = capturedParameters.stream().map(Object::getClass).toArray(Class[]::new);
            final int paramsReplacedWithCaptures = Math.min(capturedParamCount, implParamCount);
            invocationType = implType.dropParameterTypes(0,
                    paramsReplacedWithCaptures);
            for (int i = 0; i < capturedParameters.size(); i++) {
                final Class<?> implParamType = implType.parameterType(i);
                if (implParamType.isPrimitive()) {
                    capturedTypes[i] = implParamType;
                }
            }
        } else {
            capturedTypes = EMPTY_CLASS_ARRAY;
            invocationType = implType;
        }
        final MethodType factoryType = MethodType.methodType(parameters.functionalInterface, capturedTypes);
        final MethodType descriptorType = descriptor.methodType;
        try {
            final CallSite callSite;
            if (parameters.bridgeOverloadTypes.isEmpty() && parameters.markerInterfaces.isEmpty()
                    && !parameters.serializable) {
                callSite = LambdaMetafactory.metafactory(lookup, descriptor.methodName, factoryType,
                        descriptorType, implementation, invocationType);
            } else {
                final ArrayList<Object> additionalParameters = new ArrayList<>(4);
                additionalParameters.add(descriptorType);
                additionalParameters.add(implementation);
                additionalParameters.add(invocationType);
                additionalParameters.add(0);
                int flags = 0;
                final MethodHandles.Lookup outputLookup;
                if (parameters.serializable) {
                    flags |= FLAG_SERIALIZABLE;
                    outputLookup = serialLookup;
                } else {
                    outputLookup = lookup;
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
                callSite = LambdaMetafactory.altMetafactory(outputLookup, descriptor.methodName, factoryType,
                        additionalParameters.toArray());
            }
            return (T) callSite.getTarget().invokeWithArguments(capturedParameters);
        } catch (final Throwable t) {
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private static Class<?> classForSlashDelimitedName(final String slashDelimitedName) {
        try {
            return Class.forName(slashDelimitedName.replace('/', '.'));
        } catch (final ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static Executable findMethod(final SerializedLambdaMethodDescription methodDescription) {
        return getDefaultInstance().cacheManager.findMethod(methodDescription);
    }

    static Executable findMethodUncached(SerializedLambdaMethodDescription methodDescription_) {
        try {
            final Class<?> implClass = LambdaMetafactoryWrapper.classForSlashDelimitedName(methodDescription_.slashDelimitedClassName);
            final Class<?>[] parameterTypes = MethodType.fromMethodDescriptorString(methodDescription_.methodSignature,
                    LambdaMetafactoryWrapper.class.getClassLoader()).parameterArray();
            if ("<init>".equals(methodDescription_.methodName)) {
                return implClass.getDeclaredConstructor(parameterTypes);
            }
            return implClass.getDeclaredMethod(methodDescription_.methodName, parameterTypes);
        } catch (final NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused") // used reflectively
    private static Object $deserializeLambda$(final SerializedLambda serializedLambda) {
        return getDefaultInstance().cacheManager.deserializeLambda(serializedLambda);
    }

    static Object deserializeLambdaUncached(SerializedLambda lambda) {
        final Executable implementation = LambdaMetafactoryWrapper.findMethod(new SerializedLambdaMethodDescription(
                lambda.getImplClass(), lambda.getImplMethodName(),
                lambda.getImplMethodSignature()));
        final Class<?> functionalInterface = LambdaMetafactoryWrapper.classForSlashDelimitedName(lambda.getFunctionalInterfaceClass());
        final ArrayList<Object> capturedParams = new ArrayList<>(lambda.getCapturedArgCount());
        for (int i = 0; i < lambda.getCapturedArgCount(); i++) {
            capturedParams.add(lambda.getCapturedArg(i));
        }
        return getDefaultInstance().wrap(implementation,
                Parameters.builder(functionalInterface)
                        .serializable(true)
                        .addCapturedParameters(capturedParams)
                        .build());
    }

    protected <T> FunctionalInterfaceDescriptor getDescriptor(final Class<? super T> functionalInterface) {
        if (!LambdaMetafactoryWrapper.isReferencedByClassLoader(functionalInterface)) {
            return ANON_AND_HIDDEN_DESCRIPTORS.computeIfAbsent(functionalInterface, this::getDescriptorUncached);
        }
        return LambdaMetafactoryWrapper.getClassLoaderSpecificCache(functionalInterface).descriptors
                .computeIfAbsent(functionalInterface, this::getDescriptorUncached);
    }

    protected MethodHandle getUnreflectedImplementationUncached(final Executable implementation) {
        final MethodHandles.Lookup lookup = this.lookup.in(implementation.getDeclaringClass());
        try {
            implementation.setAccessible(true);
            final MethodHandle handle;
            if (implementation instanceof Method) {
                handle = lookup.unreflect((Method) implementation);
            } else if (implementation instanceof Constructor<?>) {
                handle = lookup.unreflectConstructor((Constructor<?>) implementation);
            } else {
                throw new IllegalArgumentException(implementation + " is not a Constructor or Method");
            }
            try {
                lookup.revealDirect(handle);
                return handle;
            } catch (final IllegalArgumentException e) {
                try {
                    return lookup.unreflect(MethodHandle.class.getDeclaredMethod("invokeExact", Object[].class)).bindTo(handle);
                } catch (final IllegalAccessException | NoSuchMethodException ex) {
                    throw new RuntimeException(ex);
                }
            }
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T wrap(final Executable implementation, final Parameters<T> parameters) {
        final Class<?> declaringClass = implementation.getDeclaringClass();
        if (!LambdaMetafactoryWrapper.isReferencedByClassLoader(declaringClass)) {
            return (T) ANON_AND_HIDDEN_WRAPPERS
                    .computeIfAbsent(implementation, impl -> LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap())
                    .computeIfAbsent(parameters, params -> wrap(implementation, params));
        }
        return (T) LambdaMetafactoryWrapper.getClassLoaderSpecificCache(declaringClass)
                .computeWrapperIfAbsent(implementation, parameters, this::wrapUncached);
    }

    private static boolean isReferencedByClassLoader(final Class<?> declaringClass) {
        return !declaringClass.isAnonymousClass() && !declaringClass.isHidden();
    }

    static <K, V> Map<K, V> newThreadSafeWeakKeyMap() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    protected <T> FunctionalInterfaceDescriptor getDescriptorUncached(final Class<? super T> functionalInterface) {
        final List<Method> abstractMethods = Arrays.stream(functionalInterface.getMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toList();
        if (abstractMethods.size() != 1) {
            throw new IllegalArgumentException("Expected class " + functionalInterface + " to have 1 abstract method, but it has " +
                    abstractMethods.size());
        }
        final Method abstractMethod = abstractMethods.getFirst();
        final MethodType abstractMethodType;
        try {
            abstractMethodType = lookup.unreflect(abstractMethod).type();
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return new FunctionalInterfaceDescriptor(abstractMethodType.dropParameterTypes(0, 1), abstractMethod.getName(),
                MethodType.methodType(functionalInterface));
    }

    public <T> T wrap(final Executable implementation, final Class<? super T> functionalInterface) {
        return wrap(implementation, Parameters.<T>builder(functionalInterface)
                .build());
    }

    protected MethodHandle getUnreflectedImplementation(final Executable implementation) {
        final Class<?> declaringClass = implementation.getDeclaringClass();
        if (!LambdaMetafactoryWrapper.isReferencedByClassLoader(declaringClass)) {
            return ANON_AND_HIDDEN_UNREFLECTED.computeIfAbsent(implementation, this::getUnreflectedImplementationUncached);
        }
        return LambdaMetafactoryWrapper.getClassLoaderSpecificCache(declaringClass)
                .unreflected.computeIfAbsent(implementation, this::getUnreflectedImplementationUncached);
    }

    private static class DefaultInstanceLazyLoader {
        static final LambdaMetafactoryWrapper DEFAULT_INSTANCE = new LambdaMetafactoryWrapper(MethodHandles.lookup());
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
        public static <T> Builder<T> builder(final Class<? super T> functionalInterface) {
            return new Builder<>(functionalInterface);
        }

        public static class Builder<T> {
            // Defaults
            private boolean serializable = false;
            private final ArrayList<MethodType> bridgeOverloadTypes = new ArrayList<>();
            private final ArrayList<Object> capturedParameters = new ArrayList<>(); // may include nulls
            private final ArrayList<Class<?>> markerInterfaces = new ArrayList<>();
            private final Class<? super T> functionalInterface;

            Builder(final Class<? super T> functionalInterface) {
                this.functionalInterface = requireNonNull(functionalInterface);
            }

            public Builder<T> serializable(final boolean serializable) {
                this.serializable = serializable;
                return this;
            }

            @SuppressWarnings("UnusedReturnValue")
            public Builder<T> addMarkerInterface(final Class<?> markerInterface) {
                markerInterfaces.add(requireNonNull(markerInterface));
                return this;
            }

            public Builder<T> addMarkerInterfaces(final Collection<? extends Class<?>> markerInterfaces) {
                this.markerInterfaces.addAll(markerInterfaces);
                return this;
            }

            public Builder<T> clearMarkerInterfaces() {
                this.markerInterfaces.clear();
                return this;
            }

            public Builder<T> addBridgeOverload(final MethodType bridgeOverloadType) {
                this.bridgeOverloadTypes.add(requireNonNull(bridgeOverloadType));
                return this;
            }

            public Builder<T> addBridgeOverloads(final Collection<? extends MethodType> bridgeOverloadTypes) {
                this.bridgeOverloadTypes.addAll(bridgeOverloadTypes);
                return this;
            }

            public Builder<T> clearBridgeOverloads() {
                this.bridgeOverloadTypes.clear();
                return this;
            }

            public Builder<T> addCapturedParameter(final Object capturedParameter) {
                capturedParameters.add(capturedParameter);
                return this;
            }

            public Builder<T> addCapturedParameters(final Collection<?> capturedParameters) {
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
    public boolean equals(final Object obj) {
        return (obj == this) || obj != null &&
                (obj.getClass() == getClass() && lookup == ((LambdaMetafactoryWrapper)obj).lookup);
    }

    record SerializedLambdaMethodDescription(
            String slashDelimitedClassName,
            String methodName,
            String methodSignature
    ) {}

    private static class ClassLoaderSpecificCache {
        final ConcurrentHashMap<Class<?>, FunctionalInterfaceDescriptor> descriptors = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Executable, MethodHandle> unreflected = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Executable, Map<Parameters<?>, Object>> cachedWrappers
                = new ConcurrentHashMap<>();

        Object computeWrapperIfAbsent(final Executable implementation, final Parameters<?> parameters,
                                      final BiFunction<Executable, Parameters<?>, Object> wrappingFunction) {
            return cachedWrappers.computeIfAbsent(implementation, impl -> LambdaMetafactoryWrapper.newThreadSafeWeakKeyMap())
                    .computeIfAbsent(parameters, params -> wrappingFunction.apply(implementation, params));
        }

        void clear() {
            descriptors.clear();
            unreflected.clear();
            cachedWrappers.clear();
        }
    }
}
