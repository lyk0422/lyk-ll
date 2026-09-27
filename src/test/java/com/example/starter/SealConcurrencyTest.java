package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.SealService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 铅封并发边界：同键受控调节唯一消费额度；复封与受控调节竞争唯一，不留撕裂状态。
 */
class SealConcurrencyTest extends IntegrationTestBase {

    @Autowired
    private SealService sealService;
    @Autowired
    private AdjustmentService adjustmentService;

    private record Outcome(Object result, Throwable error) {
    }

    private List<Outcome> runConcurrently(int threads, IntFunction<Object> action)
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

    private int quotaUsedOf(long unsealId) {
        Integer used = jdbc.queryForObject(
                "SELECT quota_used FROM unseal_order WHERE id = ?", Integer.class, unsealId);
        return used == null ? -1 : used;
    }

    @Test
    void 同键并发受控调节唯一受理且只消费一次额度() throws Exception {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        ValveSeal seal = sealService.registerSeal(1L, "req-seal-cc", "SEAL-CC", 0);
        UnsealOrder order = sealService.approveUnseal(seal.id(), "req-u-cc", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));

        List<Outcome> outcomes = runConcurrently(4,
                i -> adjustmentService.issueControlledAdjustment(
                        order.id(), "req-ca-cc", 1, new BigDecimal("5")));

        assertThat(outcomes).allMatch(o -> o.error() == null);
        long distinctTaskIds = outcomes.stream()
                .map(o -> ((AdjustmentTask) o.result()).id()).distinct().count();
        assertThat(distinctTaskIds).isEqualTo(1);
        // 最终数据：唯一任务、唯一版本、唯一额度消费
        assertThat(countOf("adjustment_task")).isEqualTo(1);
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
        assertThat(quotaUsedOf(order.id())).isEqualTo(1);
        assertThat(countOf("unseal_quota_usage")).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
    }

    @Test
    void 复封与受控调节竞争唯一且不留撕裂状态() throws Exception {
        for (int round = 0; round < 3; round++) {
            resetDatabase();
            submitReadings(40.00, 42.50, 41.00, 41.00);
            ValveSeal seal = sealService.registerSeal(1L, "req-seal-race-" + round, "SEAL-RACE-" + round, 0);
            UnsealOrder order = sealService.approveUnseal(seal.id(), "req-u-race-" + round, 1, 0,
                    "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
            long unsealId = order.id();
            final int roundNo = round;

            List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                    ? adjustmentService.issueControlledAdjustment(
                            unsealId, "req-ca-race-" + roundNo, 1, new BigDecimal("5"))
                    : sealService.reseal(1L, "req-rs-race-" + roundNo, 1,
                            "SEAL-RACE-NEW-" + roundNo, "甲", "乙", 0));

            long adjustOk = outcomes.stream()
                    .filter(o -> o.result() instanceof AdjustmentTask).count();
            long resealOk = outcomes.stream()
                    .filter(o -> o.result() instanceof ValveSeal).count();
            // 竞争唯一：恰有一个获胜（调节先则复封因开度版本已变被拒；复封先则调节因解封终结被拒）
            assertThat(adjustOk + resealOk).isEqualTo(1);

            if (adjustOk == 1) {
                // 调节先提交：额度已消费、版本已追加，复封被拒绝，旧解封仍有效
                assertThat(quotaUsedOf(unsealId)).isEqualTo(1);
                assertThat(countOf("valve_opening_version")).isEqualTo(1);
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
                assertThat(sealService.getUnseal(unsealId).status())
                        .isEqualTo(UnsealOrder.UnsealStatus.ACTIVE);
            } else {
                // 复封先提交：旧解封已终结，不得再追加调节版本，额度未消费
                assertThat(quotaUsedOf(unsealId)).isEqualTo(0);
                assertThat(countOf("valve_opening_version")).isEqualTo(0);
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(sealService.getUnseal(unsealId).status())
                        .isEqualTo(UnsealOrder.UnsealStatus.CLOSED);
                assertThat(sealService.getActiveSeal(1L).sealNo())
                        .isEqualTo("SEAL-RACE-NEW-" + round);
            }
        }
    }
}
