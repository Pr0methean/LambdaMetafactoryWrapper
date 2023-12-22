package io.github.pr0methean.invoke;

public class LambdaMetafactoryNoCacheTest extends LambdaMetafactoryWrapperTest {
    @Override
    protected LambdaMetafactoryWrapper createWrapper() {
        return new LambdaMetafactoryWrapper(getLookup(), LambdaMetafactoryNoopCacheManager.getInstance());
    }
}
