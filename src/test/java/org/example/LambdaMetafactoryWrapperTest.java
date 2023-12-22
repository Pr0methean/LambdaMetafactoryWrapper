package org.example;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

import static org.junit.jupiter.api.Assertions.*;

class LambdaMetafactoryWrapperTest {
    // TODO: Caching, bridges, marker interfaces

    protected LambdaMetafactoryWrapper createWrapper() {
        return new LambdaMetafactoryWrapper(getLookup());
    }

    protected final MethodHandles.Lookup getLookup() {
        return MethodHandles.lookup();
    }

    private static String getGreeting() {
        return "Hello World";
    }

    private static String getGreetingForInt(int i) {
        return "Hello, my favorite number is " + i;
    }

    private static int intFromTwoStrings(String a, String b) {
        return Objects.hash(a, b);
    }

    private static class InstanceTester {
        private String getGreetingFromInstance() {
            return getGreeting();
        }

        private String getGreetingFor(double d) {
            return "Hello, my favorite number is " + d;
        }
    }

    @Test
    public void testEqualsAndHashCode() {
        EqualsVerifier.forClass(LambdaMetafactoryWrapper.class)
                .withPrefabValues(MethodHandles.Lookup.class, MethodHandles.lookup(), MethodHandles.publicLookup())
                .withNonnullFields("lookup")
                .withIgnoredFields("serialLookup")
                .withIgnoredFields("cacheManager")
                .usingGetClass()
                .verify();
    }
    @Test
    public void testSimpleStaticMethod() throws NoSuchMethodException {
        assertSame(getGreeting(), createWrapper()
                .wrap(LambdaMetafactoryWrapperTest.class.getDeclaredMethod("getGreeting"), Supplier.class).get());
        assertEquals(intFromTwoStrings("Hello", "world!"), createWrapper()
                .<ToIntBiFunction<String, String>>wrap(LambdaMetafactoryWrapperTest.class.getDeclaredMethod("intFromTwoStrings", String.class, String.class),
                        ToIntBiFunction.class).applyAsInt("Hello", "world!"));
    }

    @Test
    public void testSimpleInstanceMethod() throws NoSuchMethodException {
        assertSame(getGreeting(), createWrapper()
                .<Function<InstanceTester, String>>wrap(InstanceTester.class
                        .getDeclaredMethod("getGreetingFromInstance"), Function.class)
                .apply(new InstanceTester()));
    }

    @Test
    public void testInstanceMethodCapturingInstance() throws NoSuchMethodException {
        LambdaMetafactoryWrapper.Parameters.Builder<Supplier<String>>
                builder = LambdaMetafactoryWrapper.Parameters.builder(Supplier.class);
        InstanceTester tester = new InstanceTester();
        builder.addCapturedParameter(tester);
        assertSame(getGreeting(), createWrapper()
                .wrap(InstanceTester.class.getDeclaredMethod("getGreetingFromInstance"), builder.build()).get());
    }

    @Test
    public void testStaticMethodCapturing() throws NoSuchMethodException {
        LambdaMetafactoryWrapper.Parameters.Builder<Supplier<String>>
                builder = LambdaMetafactoryWrapper.Parameters.builder(Supplier.class);
        builder.addCapturedParameter(5);
        assertNotNull(createWrapper()
                .wrap(LambdaMetafactoryWrapperTest.class.getDeclaredMethod("getGreetingForInt", int.class),
                        builder.build()).get());
    }

    @Test
    public void testStaticMethodCapturingTwoParameters() throws NoSuchMethodException {
        LambdaMetafactoryWrapper.Parameters.Builder<IntSupplier>
                builder = LambdaMetafactoryWrapper.Parameters.builder(IntSupplier.class);
        builder.addCapturedParameter("Hello");
        builder.addCapturedParameter("world");
        createWrapper().wrap(LambdaMetafactoryWrapperTest.class.getDeclaredMethod(
                        "intFromTwoStrings", String.class, String.class),
                        builder.build()).getAsInt();
    }

    @Test
    public void testInstanceMethodCapturingInstanceAndParameter() throws NoSuchMethodException {
        LambdaMetafactoryWrapper.Parameters.Builder<Supplier<String>>
                builder = LambdaMetafactoryWrapper.Parameters.builder(Supplier.class);
        InstanceTester tester = new InstanceTester();
        builder.addCapturedParameter(tester);
        builder.addCapturedParameter(3.141);
        assertNotNull(getGreeting(), createWrapper()
                .wrap(InstanceTester.class.getDeclaredMethod("getGreetingFor", double.class), builder.build()).get());
    }

    @Test
    public void testVariadicMethod() throws NoSuchMethodException {
        LambdaMetafactoryWrapper.Parameters.Builder<IntSupplier>
                builder = LambdaMetafactoryWrapper.Parameters.builder(IntSupplier.class);
        builder.addCapturedParameter("Hello");
        builder.addCapturedParameter("world");
        createWrapper().wrap(Objects.class.getDeclaredMethod("hash", Object[].class), builder.build()).getAsInt();
    }
}