package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.CoupledAdjustmentService;
import com.example.starter.service.SealService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * 联调并发边界：同键联调唯一受理；联调与单阀调节、复封竞争按共享阀版本裁决，
 * 不留半组成功的撕裂状态。
 */
class CoupledConcurrencyTest extends IntegrationTestBase {

    private static final Instant REQ_AT = Instant.parse("2026-09-26T01:00:00Z");

    @Autowired
    private CoupledAdjustmentService coupledService;
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

    private void setOpening(long unitId, String opening) {
        jdbc.update("UPDATE valve SET opening_percent = ? WHERE unit_id = ?",
                new BigDecimal(opening), unitId);
    }

    private CoupledAdjustmentService.GroupView createGroup(String requestId, long... valveIds) {
        List<CoupledAdjustmentService.MemberSpec> members = java.util.Arrays.stream(valveIds)
                .mapToObj(v -> new CoupledAdjustmentService.MemberSpec(v, 1))
                .toList();
        return coupledService.createGroup(STATION_ID, requestId, members);
    }

    private CoupledAdjustmentService.TargetSpec target(long valveId, int version, int opening) {
        return new CoupledAdjustmentService.TargetSpec(valveId, version, opening, null, null);
    }

    @Test
    void 同键并发联调唯一受理且只消费一次() throws Exception {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-cc", 1L, 2L);
        long groupId = group.group().id();

        List<Outcome> outcomes = runConcurrently(4, i -> coupledService.submit(groupId,
                "req-c-cc", 1, REQ_AT, List.of(target(1L, 0, 50), target(2L, 0, 50))));

        assertThat(outcomes).allMatch(o -> o.error() == null);
        long distinctIds = outcomes.stream()
                .map(o -> ((CoupledAdjustmentService.CoupledView) o.result()).coupled().id())
                .distinct().count();
        assertThat(distinctIds).isEqualTo(1);
        // 最终数据：唯一联调记录、两条任务、两阀各一个版本，组版本只推进一次
        assertThat(countOf("coupled_adjustment")).isEqualTo(1);
        assertThat(countOf("coupled_adjustment_item")).isEqualTo(2);
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(coupledService.getGroup(groupId).group().version()).isEqualTo(2);
    }

    @Test
    void 联调与复封竞争唯一且不留撕裂状态() throws Exception {
        for (int round = 0; round < 3; round++) {
            resetDatabase();
            setOpening(1L, "40.00");
            setOpening(2L, "60.00");
            ValveSeal seal1 = sealService.registerSeal(1L, "req-s-rc1-" + round, "SEAL-RC1-" + round, 0);
            ValveSeal seal2 = sealService.registerSeal(2L, "req-s-rc2-" + round, "SEAL-RC2-" + round, 0);
            UnsealOrder order1 = sealService.approveUnseal(seal1.id(), "req-u-rc1-" + round, 1, 0,
                    "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
            UnsealOrder order2 = sealService.approveUnseal(seal2.id(), "req-u-rc2-" + round, 1, 0,
                    "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
            CoupledAdjustmentService.GroupView group = createGroup("req-g-rc-" + round, 1L, 2L);
            long groupId = group.group().id();
            long unsealId1 = order1.id();
            long unsealId2 = order2.id();
            final int roundNo = round;

            List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                    ? coupledService.submit(groupId, "req-c-rc-" + roundNo, 1, REQ_AT,
                            List.of(new CoupledAdjustmentService.TargetSpec(1L, 0, 50,
                                            unsealId1, 1),
                                    new CoupledAdjustmentService.TargetSpec(2L, 0, 50,
                                            unsealId2, 1)))
                    : sealService.reseal(1L, "req-rs-rc-" + roundNo, 1,
                            "SEAL-RC-NEW-" + roundNo, "甲", "乙", 0));

            long coupledOk = outcomes.stream()
                    .filter(o -> o.result() instanceof CoupledAdjustmentService.CoupledView).count();
            long resealOk = outcomes.stream()
                    .filter(o -> o.result() instanceof ValveSeal).count();
            // 竞争唯一：恰有一个获胜（联调先则复封因开度版本已变被拒；复封先则联调因解封终结被拒）
            assertThat(coupledOk + resealOk).isEqualTo(1);

            if (coupledOk == 1) {
                // 联调先提交：两阀一起到位、额度各消费一次，复封被拒绝
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(countOf("valve_opening_version")).isEqualTo(2);
                assertThat(countOf("unseal_quota_usage")).isEqualTo(2);
                assertThat(sealService.getUnseal(unsealId1).quotaUsed()).isEqualTo(1);
                assertThat(sealService.getUnseal(unsealId2).quotaUsed()).isEqualTo(1);
            } else {
                // 复封先提交：旧解封已终结，全组零变化，额度未消费
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
                assertThat(countOf("valve_opening_version")).isZero();
                assertThat(countOf("coupled_adjustment")).isZero();
                assertThat(countOf("unseal_quota_usage")).isZero();
                assertThat(sealService.getUnseal(unsealId1).status())
                        .isEqualTo(UnsealOrder.UnsealStatus.CLOSED);
                assertThat(sealService.getActiveSeal(1L).sealNo())
                        .isEqualTo("SEAL-RC-NEW-" + round);
            }
        }
    }

    @Test
    void 联调与单阀调节竞争按共享阀版本唯一生效() throws Exception {
        for (int round = 0; round < 3; round++) {
            resetDatabase();
            setOpening(1L, "40.00");
            setOpening(2L, "60.00");
            submitReadings(40.00, 42.50, 41.00, 41.00);
            CoupledAdjustmentService.GroupView group = createGroup("req-g-sa-" + round, 1L, 2L);
            long groupId = group.group().id();
            final int roundNo = round;

            List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                    ? coupledService.submit(groupId, "req-c-sa-" + roundNo, 1, REQ_AT,
                            List.of(target(1L, 0, 50), target(2L, 0, 50)))
                    : adjustmentService.issueAdjustment(STATION_ID, "req-a-sa-" + roundNo, 1L,
                            new BigDecimal("5")));

            long coupledOk = outcomes.stream()
                    .filter(o -> o.result() instanceof CoupledAdjustmentService.CoupledView).count();
            long singleOk = outcomes.stream()
                    .filter(o -> o.result() instanceof AdjustmentTask).count();
            // 竞争唯一：单阀先则联调因共享阀版本变旧被拒；联调先则单阀因站内执行中任务被拒
            assertThat(coupledOk + singleOk).isEqualTo(1);

            if (coupledOk == 1) {
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(countOf("valve_opening_version")).isEqualTo(2);
            } else {
                // 单阀先动（欠流开大 5）：联调全组零变化
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("45.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
                assertThat(countOf("valve_opening_version")).isEqualTo(1);
                assertThat(countOf("coupled_adjustment")).isZero();
            }
        }
    }
}
