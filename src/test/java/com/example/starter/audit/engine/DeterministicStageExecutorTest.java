package com.example.starter.audit.engine;

import com.example.starter.audit.domain.StageContext;
import com.example.starter.audit.domain.StageFailureException;
import com.example.starter.audit.domain.StageInterruptedException;
import com.example.starter.audit.domain.StageProduct;
import com.example.starter.audit.model.Bindings;
import com.example.starter.audit.model.Stage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确定性阶段执行器纯单元测试：不启动 Spring，不接触数据库。
 */
class DeterministicStageExecutorTest {

    private final DigestSupport digestSupport = new DigestSupport();
    private final DeterministicStageExecutor executor =
            new DeterministicStageExecutor(digestSupport);

    private final Bindings bindings =
            new Bindings("code-1", "config-1", "map-1", "bag-1", 77L);

    @Test
    void sameInputsProduceIdenticalProducts() throws Exception {
        StageContext a = new StageContext(1L, Stage.PLANNING, bindings,
                "up-a", 1, "default", null);
        StageContext b = new StageContext(9L, Stage.PLANNING, bindings,
                "up-a", 1, "default", null);

        StageProduct pa = executor.execute(a);
        StageProduct pb = executor.execute(b);

        assertThat(pb.metrics()).isEqualTo(pa.metrics());
        assertThat(pb.artifactBytes()).isEqualTo(pa.artifactBytes());
        assertThat(pb.replayCommand()).isEqualTo(pa.replayCommand());
        // 产物字节的 SHA-256 稳定
        assertThat(digestSupport.sha256Hex(pb.artifactBytes()))
                .isEqualTo(digestSupport.sha256Hex(pa.artifactBytes()));
    }

    @Test
    void differentSeedOrUpstreamChangesOutput() throws Exception {
        StageProduct base = executor.execute(new StageContext(1L, Stage.PERCEPTION,
                bindings, digestRoot(), 1, "default", null));
        StageProduct otherSeed = executor.execute(new StageContext(1L, Stage.PERCEPTION,
                new Bindings("code-1", "config-1", "map-1", "bag-1", 78L),
                digestRoot(), 1, "default", null));
        StageProduct otherUpstream = executor.execute(new StageContext(1L, Stage.PERCEPTION,
                bindings, "a".repeat(64), 1, "default", null));

        assertThat(otherSeed.metrics()).isNotEqualTo(base.metrics());
        assertThat(otherUpstream.metrics()).isNotEqualTo(base.metrics());
    }

    @Test
    void failureInjectedOnlyOnFirstAttempt() {
        StageContext first = new StageContext(1L, Stage.LOCALIZATION, bindings,
                digestRoot(), 1, "default", "fail:localization");
        StageContext retry = new StageContext(1L, Stage.LOCALIZATION, bindings,
                digestRoot(), 2, "default", "fail:localization");

        assertThatThrownBy(() -> executor.execute(first))
                .isInstanceOf(StageFailureException.class)
                .hasMessageContaining("localization");
        assertThatCodeDoesNotThrow(retry);
    }

    @Test
    void interruptionInjectedOnlyOnTargetedStage() {
        StageContext control = new StageContext(1L, Stage.CONTROL, bindings,
                digestRoot(), 1, "default", "interrupt:control");
        StageContext planning = new StageContext(1L, Stage.PLANNING, bindings,
                digestRoot(), 1, "default", "interrupt:control");

        assertThatThrownBy(() -> executor.execute(control))
                .isInstanceOf(StageInterruptedException.class);
        assertThatCodeDoesNotThrow(planning);
    }

    @Test
    void extendedProfileAddsMetric() throws Exception {
        StageProduct standard = executor.execute(new StageContext(1L, Stage.PERCEPTION,
                bindings, digestRoot(), 1, "default", null));
        StageProduct extended = executor.execute(new StageContext(1L, Stage.PERCEPTION,
                bindings, digestRoot(), 1, "extended", null));

        assertThat(standard.metrics()).containsOnlyKeys("objects_detected",
                "confidence_mean", "latency_ms");
        assertThat(extended.metrics()).containsKey("objects_classified");
    }

    private void assertThatCodeDoesNotThrow(StageContext context) {
        try {
            StageProduct product = executor.execute(context);
            assertThat(product.metrics()).isNotEmpty();
        } catch (StageFailureException | StageInterruptedException e) {
            throw new AssertionError("重试批次不应再注入故障", e);
        }
    }

    private String digestRoot() {
        return "0".repeat(64);
    }
}
