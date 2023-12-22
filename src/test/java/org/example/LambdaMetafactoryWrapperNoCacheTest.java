package org.example;

import java.lang.invoke.MethodHandles;

public class LambdaMetafactoryWrapperNoCacheTest extends LambdaMetafactoryWrapperTest {
    @Override
    protected LambdaMetafactoryWrapper createWrapper() {
        return new LambdaMetafactoryWrapper(MethodHandles.lookup(), new LambdaMetafactoryNoopCacheManager());
    }
}
