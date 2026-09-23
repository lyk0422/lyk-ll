package com.example.starter.compiler.cache;

import com.example.starter.compiler.api.DependencyInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultCacheKeyFactory} 的单元测试：键必须覆盖入口、依赖摘要与参数。
 */
class CacheKeyFactoryTest {

    private final DefaultCacheKeyFactory factory = new DefaultCacheKeyFactory();

    private final List<DependencyInfo> dependencies = List.of(
            new DependencyInfo("main.xacro", "a".repeat(64)),
            new DependencyInfo("sub/links.xacro", "b".repeat(64)));

    @Test
    void sameInputsProduceSameKey() {
        CacheKey first = factory.create("main.xacro", dependencies, Map.of("length", "0.4"));
        CacheKey second = factory.create("main.xacro",
                List.of(new DependencyInfo("sub/links.xacro", "b".repeat(64)),
                        new DependencyInfo("main.xacro", "a".repeat(64))),
                Map.of("length", "0.4"));

        assertThat(first.keyHash()).isEqualTo(second.keyHash());
        assertThat(first.keyHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void parameterOrderDoesNotChangeKey() {
        CacheKey first = factory.create("main.xacro", dependencies, Map.of("a", "1", "b", "2"));
        CacheKey second = factory.create("main.xacro", dependencies, Map.of("b", "2", "a", "1"));
        assertThat(first.keyHash()).isEqualTo(second.keyHash());
    }

    @Test
    void parameterValueChangeInvalidatesKey() {
        CacheKey first = factory.create("main.xacro", dependencies, Map.of("length", "0.4"));
        CacheKey second = factory.create("main.xacro", dependencies, Map.of("length", "0.5"));
        assertThat(first.keyHash()).isNotEqualTo(second.keyHash());
    }

    @Test
    void addedParameterInvalidatesKey() {
        CacheKey first = factory.create("main.xacro", dependencies, Map.of());
        CacheKey second = factory.create("main.xacro", dependencies, Map.of("length", "0.5"));
        assertThat(first.keyHash()).isNotEqualTo(second.keyHash());
    }

    @Test
    void dependencyContentChangeInvalidatesKey() {
        CacheKey first = factory.create("main.xacro", dependencies, Map.of());
        List<DependencyInfo> changed = List.of(
                new DependencyInfo("main.xacro", "a".repeat(64)),
                new DependencyInfo("sub/links.xacro", "c".repeat(64)));
        CacheKey second = factory.create("main.xacro", changed, Map.of());
        assertThat(first.keyHash()).isNotEqualTo(second.keyHash());
    }

    @Test
    void entryPathChangeInvalidatesKey() {
        CacheKey first = factory.create("main.xacro", dependencies, Map.of());
        CacheKey second = factory.create("other.xacro", dependencies, Map.of());
        assertThat(first.keyHash()).isNotEqualTo(second.keyHash());
    }
}
