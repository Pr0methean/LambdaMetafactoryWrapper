package org.example;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LambdaMetafactoryWrapperTest {
    protected LambdaMetafactoryWrapper createWrapper() {
        return new LambdaMetafactoryWrapper(MethodHandles.lookup());
    }

    private static String getGreeting() {
        return "Hello World";
    }

    private static class InstanceTester {
        private String getGreetingFromInstance() {
            return getGreeting();
        }
    }

    @Test
    public void testEqualsAndHashCode() {
        EqualsVerifier.forClass(LambdaMetafactoryWrapper.class)
                .withPrefabValues(MethodHandles.Lookup.class, MethodHandles.lookup(), MethodHandles.publicLookup())
                .withNonnullFields("lookup")
                .usingGetClass()
                .verify();
    }
    @Test
    public void testSimpleStaticMethod() throws NoSuchMethodException {
        assertSame(getGreeting(), createWrapper()
                .wrap(LambdaMetafactoryWrapperTest.class.getDeclaredMethod("getGreeting"), Supplier.class).get());
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
                builder = LambdaMetafactoryWrapper.Parameters.builder();
        builder.functionalInterface(Supplier.class);
        InstanceTester tester = new InstanceTester();
        builder.addCapturedParameter(tester);
        assertSame(getGreeting(), createWrapper()
                .wrap(InstanceTester.class.getDeclaredMethod("getGreetingFromInstance"), builder.build()).get());
    }
}