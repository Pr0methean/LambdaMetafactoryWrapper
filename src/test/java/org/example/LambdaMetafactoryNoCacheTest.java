package org.example;

public class LambdaMetafactoryNoCacheTest extends LambdaMetafactoryWrapperTest {
    @Override
    protected LambdaMetafactoryWrapper createWrapper() {
        return new LambdaMetafactoryWrapper(getLookup(), new LambdaMetafactoryNoopCacheManager());
    }
}
