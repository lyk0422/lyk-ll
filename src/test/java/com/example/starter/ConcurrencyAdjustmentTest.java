package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界：同一换热站同时刻只允许一个执行中调节任务；同键并发重放唯一受理。
 */
class ConcurrencyAdjustmentTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;

    private record Outcome(AdjustmentTask task, Throwable error) {
    }

    private List<Outcome> runConcurrently(int threads, java.util.function.IntFunction<AdjustmentTask> action)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome(action.apply(index), null);
                } catch (Throwable e) {
                    return new Outcome(null, e);
                }
            }));
        }
        gate.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            try {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new IllegalStateException("并发用例执行超时或失败", e);
            }
        }
        pool.shutdownNow();
        return outcomes;
    }

    @Test
    void 并发下达调节令站内唯一受理() throws Exception {
        submitReadings(40.00, 42.50, 41.00, 41.00);

        List<Outcome> outcomes = runConcurrently(4,
                i -> adjustmentService.issueAdjustment(
                        STATION_ID, "req-concurrent-" + i, 1L, new BigDecimal("5")));

        long succeeded = outcomes.stream().filter(o -> o.task() != null).count();
        long conflicted = outcomes.stream()
                .filter(o -> o.error() instanceof ConflictException).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(3);
        // 最终数据：仅一个执行中任务，开度只变更一次，版本链只有一条
        assertThat(countOf("adjustment_task")).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 同键同参并发重放唯一受理且结果一致() throws Exception {
        submitReadings(40.00, 42.50, 41.00, 41.00);

        List<Outcome> outcomes = runConcurrently(4,
                i -> adjustmentService.issueAdjustment(
                        STATION_ID, "req-same-key", 1L, new BigDecimal("5")));

        assertThat(outcomes).allMatch(o -> o.error() == null);
        long distinctTaskIds = outcomes.stream().map(o -> o.task().id()).distinct().count();
        assertThat(distinctTaskIds).isEqualTo(1);
        assertThat(countOf("adjustment_task")).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }
}
