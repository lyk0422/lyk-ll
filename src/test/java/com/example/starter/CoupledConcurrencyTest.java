package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.CoupledAdjustment;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.CoupledService;
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
 * 联调并发边界：同键联调唯一受理；联调与复封、联调与单阀调节按共享阀版本竞争唯一，
 * 不留半组成功的撕裂状态。
 */
class CoupledConcurrencyTest extends IntegrationTestBase {

    @Autowired
    private CoupledService coupledService;
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

    private void setOpening(long valveId, String opening) {
        jdbc.update("UPDATE valve SET opening_percent = ? WHERE id = ?",
                new BigDecimal(opening), valveId);
    }

    private CoupledService.TargetCommand target(long valveId, int opening, int version,
                                                Long unsealId) {
        return new CoupledService.TargetCommand(valveId, opening, version, unsealId);
    }

    private int quotaUsedOf(long unsealId) {
        Integer used = jdbc.queryForObject(
                "SELECT quota_used FROM unseal_order WHERE id = ?", Integer.class, unsealId);
        return used == null ? -1 : used;
    }

    @Test
    void 同键并发联调唯一受理且只生效一次() throws Exception {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = coupledService.createGroup(STATION_ID, "req-cg-cc", List.of(
                new CoupledService.MemberCommand(1L, 1),
                new CoupledService.MemberCommand(2L, 1)));
        long groupId = group.group().id();

        List<Outcome> outcomes = runConcurrently(4,
                i -> coupledService.submitAdjustment(groupId, "req-ca-cc", T0,
                        List.of(target(1L, 50, 0, null), target(2L, 50, 0, null))));

        assertThat(outcomes).allMatch(o -> o.error() == null);
        long distinctIds = outcomes.stream()
                .map(o -> ((CoupledService.AdjustmentView) o.result()).adjustment().id())
                .distinct().count();
        assertThat(distinctIds).isEqualTo(1);
        // 最终数据：唯一联调单、两条逐阀明细、两个任务、两个版本
        assertThat(countOf("coupled_adjustment")).isEqualTo(1);
        assertThat(countOf("coupled_adjustment_item")).isEqualTo(2);
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void 复封与联调竞争唯一且不留撕裂状态() throws Exception {
        for (int round = 0; round < 3; round++) {
            resetDatabase();
            setOpening(1L, "40.00");
            setOpening(2L, "60.00");
            ValveSeal seal = sealService.registerSeal(1L, "req-seal-cr-" + round,
                    "SEAL-CR-" + round, 0);
            UnsealOrder order = sealService.approveUnseal(seal.id(), "req-u-cr-" + round, 1, 0,
                    "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
            var group = coupledService.createGroup(STATION_ID, "req-cg-cr-" + round, List.of(
                    new CoupledService.MemberCommand(1L, 1),
                    new CoupledService.MemberCommand(2L, 1)));
            long groupId = group.group().id();
            long unsealId = order.id();
            final int roundNo = round;

            List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                    ? coupledService.submitAdjustment(groupId, "req-ca-cr-" + roundNo, T0,
                            List.of(target(1L, 50, 0, unsealId), target(2L, 50, 0, null)))
                    : sealService.reseal(1L, "req-rs-cr-" + roundNo, 1,
                            "SEAL-CR-NEW-" + roundNo, "甲", "乙", 0));

            long coupledOk = outcomes.stream()
                    .filter(o -> o.result() instanceof CoupledService.AdjustmentView).count();
            long resealOk = outcomes.stream()
                    .filter(o -> o.result() instanceof ValveSeal).count();
            // 竞争唯一：恰有一个获胜（联调先则复封因开度版本已变被拒；复封先则联调因解封终结被拒）
            assertThat(coupledOk + resealOk).isEqualTo(1);

            if (coupledOk == 1) {
                // 联调先提交：两阀一起成功，额度已消费，复封被拒绝，旧解封仍有效
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(quotaUsedOf(unsealId)).isEqualTo(1);
                assertThat(countOf("valve_opening_version")).isEqualTo(2);
                assertThat(sealService.getUnseal(unsealId).status())
                        .isEqualTo(UnsealOrder.UnsealStatus.ACTIVE);
            } else {
                // 复封先提交：旧解封已终结，全组零变化，额度未消费
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
                assertThat(quotaUsedOf(unsealId)).isEqualTo(0);
                assertThat(countOf("coupled_adjustment")).isEqualTo(0);
                assertThat(countOf("valve_opening_version")).isEqualTo(0);
                assertThat(sealService.getUnseal(unsealId).status())
                        .isEqualTo(UnsealOrder.UnsealStatus.CLOSED);
                assertThat(sealService.getActiveSeal(1L).sealNo())
                        .isEqualTo("SEAL-CR-NEW-" + round);
            }
        }
    }

    @Test
    void 联调与单阀调节竞争按共享阀版本唯一生效() throws Exception {
        for (int round = 0; round < 3; round++) {
            resetDatabase();
            submitReadings(40.00, 42.50, 41.00, 41.00);
            var group = coupledService.createGroup(STATION_ID, "req-cg-sr-" + round, List.of(
                    new CoupledService.MemberCommand(1L, 1),
                    new CoupledService.MemberCommand(2L, 1)));
            long groupId = group.group().id();
            final int roundNo = round;

            // 联调 50/50 → 55/45（净额守恒）与单阀调节（单元 1 欠流开大 5）竞争
            List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                    ? coupledService.submitAdjustment(groupId, "req-ca-sr-" + roundNo, T0,
                            List.of(target(1L, 55, 0, null), target(2L, 45, 0, null)))
                    : adjustmentService.issueAdjustment(STATION_ID, "req-adj-sr-" + roundNo, 1L,
                            new BigDecimal("5")));

            long coupledOk = outcomes.stream()
                    .filter(o -> o.result() instanceof CoupledService.AdjustmentView).count();
            long singleOk = outcomes.stream()
                    .filter(o -> o.result() instanceof AdjustmentTask).count();
            // 竞争唯一：联调先则单阀调节因站内执行中任务被拒；单阀先则联调因阀版本变旧 409
            assertThat(coupledOk + singleOk).isEqualTo(1);

            if (coupledOk == 1) {
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("45.00"));
                assertThat(countOf("coupled_adjustment")).isEqualTo(1);
                assertThat(countOf("adjustment_task")).isEqualTo(2);
                assertThat(countOf("valve_opening_version")).isEqualTo(2);
            } else {
                assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
                assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
                assertThat(countOf("coupled_adjustment")).isEqualTo(0);
                assertThat(countOf("adjustment_task")).isEqualTo(1);
                assertThat(countOf("valve_opening_version")).isEqualTo(1);
            }
        }
    }
}
