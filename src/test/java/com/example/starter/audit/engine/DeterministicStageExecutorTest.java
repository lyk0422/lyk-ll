package com.example.starter.audit.engine;

import com.example.starter.audit.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性阶段执行器纯单元测试：相同输入结果一致、不同种子/上游摘要结果不同、失败注入按次数生效。
 */
class DeterministicStageExecutorTest {

    private final DeterministicStageExecutor executor = new DeterministicStageExecutor(0);

    private StageExecutor.StageExecutionContext context(Stage stage, long seed, int attempt,
                                                         Map<String, String> upstream,
                                                         String failStage, int failTimes) {
        return new StageExecutor.StageExecutionContext(
                1L, stage, seed, attempt,
                "code@1", "config@1", "map@1", "rosbag@1",
                upstream, failStage, failTimes);
    }

    @Test
    @DisplayName("相同绑定、种子与上游摘要重复执行，指标与产物SHA完全一致")
    void sameInputs_produceIdenticalOutput() {
        StageExecutionResult first = executor.execute(
                context(Stage.PLANNING, 42L, 1, Map.of("PERCEPTION", "aaa", "LOCALIZATION", "bbb"), null, 0));
        StageExecutionResult second = executor.execute(
                context(Stage.PLANNING, 42L, 2, Map.of("PERCEPTION", "aaa", "LOCALIZATION", "bbb"), null, 0));

        assertEquals(first.metrics(), second.metrics());
        assertEquals(first.artifactSha256(), second.artifactSha256());
        assertEquals(first.replayCommand(), second.replayCommand());
        // 产物字节与声明摘要一致
        assertEquals(first.artifactSha256(), DigestUtil.sha256(first.artifactContent()));
    }

    @Test
    @DisplayName("不同种子产生不同指标与产物摘要")
    void differentSeed_producesDifferentOutput() {
        StageExecutionResult a = executor.execute(
                context(Stage.PERCEPTION, 1L, 1, Map.of(), null, 0));
        StageExecutionResult b = executor.execute(
                context(Stage.PERCEPTION, 2L, 1, Map.of(), null, 0));
        assertNotEquals(a.metrics(), b.metrics());
        assertNotEquals(a.artifactSha256(), b.artifactSha256());
    }

    @Test
    @DisplayName("上游产物摘要变化导致下游产物摘要变化（依赖链纳入确定性输入）")
    void upstreamDigestChange_propagates() {
        StageExecutionResult a = executor.execute(
                context(Stage.LOCALIZATION, 9L, 1, Map.of("PERCEPTION", "digest-a"), null, 0));
        StageExecutionResult b = executor.execute(
                context(Stage.LOCALIZATION, 9L, 1, Map.of("PERCEPTION", "digest-b"), null, 0));
        assertNotEquals(a.artifactSha256(), b.artifactSha256());
    }

    @Test
    @DisplayName("失败注入：前N次执行失败，第N+1次成功；其他阶段不受影响")
    void failInjection_thenSuccessAfterAttempts() {
        StageExecutionException ex = assertThrows(StageExecutionException.class,
                () -> executor.execute(
                        context(Stage.CONTROL, 5L, 1, Map.of(), "CONTROL", 2)));
        assertEquals("CONTROL", ex.getStage());
        assertThrows(StageExecutionException.class,
                () -> executor.execute(
                        context(Stage.CONTROL, 5L, 2, Map.of(), "CONTROL", 2)));
        StageExecutionResult success = executor.execute(
                context(Stage.CONTROL, 5L, 3, Map.of(), "CONTROL", 2));
        assertNotNull(success.artifactSha256());

        // failTimes 缺省（0）时按 1 次处理
        assertThrows(StageExecutionException.class,
                () -> executor.execute(
                        context(Stage.PLANNING, 5L, 1, Map.of(), "PLANNING", 0)));
        assertNotNull(executor.execute(
                context(Stage.PLANNING, 5L, 2, Map.of(), "PLANNING", 0)));
    }

    @Test
    @DisplayName("每阶段输出约定口径的三个指标，且重放命令包含绑定版本与种子")
    void metricSpecAndReplayCommand() {
        for (Stage stage : Stage.ordered()) {
            StageExecutionResult result = executor.execute(
                    context(stage, 3L, 1, Map.of(), null, 0));
            assertEquals(DeterministicStageExecutor.METRIC_SPEC.get(stage).size(), result.metrics().size());
            assertTrue(result.replayCommand().contains("--stage " + stage.name()));
            assertTrue(result.replayCommand().contains("--seed 3"));
            assertTrue(result.replayCommand().contains("code@1"));
        }
    }
}
