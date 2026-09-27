package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealPermit;
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
 * 铅封/解封并发边界：同键受控调节不重复消费额度；复封与调节竞争唯一，
 * 已复封阀门不可能再追加调节版本。
 */
class ConcurrencySealTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = T0;
    private static final Instant WINDOW_END = T0.plus(Duration.ofHours(2));

    @Autowired
    private SealService sealService;

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

    private UnsealPermit sealAndApprove(String sealCode) {
        sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-" + sealCode, 1L, sealCode, 0));
        return sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-unseal-" + sealCode, sealCode, 0, "张三", "李四", WINDOW_START, WINDOW_END));
    }

    @Test
    void 同键受控调节并发不重复消费额度() throws Exception {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        UnsealPermit permit = sealAndApprove("SEAL-200");

        List<Outcome> outcomes = runConcurrently(4,
                i -> sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                        "req-cadj-same", STATION_ID, 1L, permit.id(), 0, new BigDecimal("5"))));

        long succeeded = outcomes.stream().filter(o -> o.result() != null).count();
        // 并发下同键：首个受理成功，其余按重放返回首次结果（均为同一任务）
        assertThat(succeeded).isEqualTo(4);
        long distinctTasks = outcomes.stream()
                .map(o -> ((AdjustmentTask) o.result()).id()).distinct().count();
        assertThat(distinctTasks).isEqualTo(1);
        // 额度只消费一次，开度只变更一次，版本链与轨迹均只有一条
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
        assertThat(sealService.getQuotaEvents(permit.id())).hasSize(1);
    }

    @Test
    void 异键受控调节并发额度与版本唯一() throws Exception {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        UnsealPermit permit = sealAndApprove("SEAL-201");

        // 4 个并发请求竞争 2 次额度：全部携带初始版本 0
        List<Outcome> outcomes = runConcurrently(4,
                i -> sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                        "req-cadj-race-" + i, STATION_ID, 1L, permit.id(), 0, new BigDecimal("5"))));

        long succeeded = outcomes.stream().filter(o -> o.result() != null).count();
        // 版本一致要求下只有首个能成功（其余版本不匹配或额度冲突）
        assertThat(succeeded).isEqualTo(1);
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(1);
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
        assertThat(countOf("adjustment_task")).isEqualTo(1);
    }

    @Test
    void 复封与调节竞争唯一且已复封不追加调节版本() throws Exception {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        UnsealPermit permit = sealAndApprove("SEAL-202");

        // 1 个复封 + 3 个调节并发竞争同一阀门
        List<Outcome> outcomes = runConcurrently(4, i -> {
            if (i == 0) {
                return sealService.reseal(new SealService.ResealCommand(
                        "req-reseal-race", "SEAL-202", 0, "SEAL-203", "赵六", "孙七"));
            }
            return sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                    "req-cadj-race2-" + i, STATION_ID, 1L, permit.id(), 0, new BigDecimal("5")));
        });

        long adjustSucceeded = outcomes.stream()
                .filter(o -> o.result() instanceof AdjustmentTask).count();
        long resealSucceeded = outcomes.stream()
                .filter(o -> o.result() instanceof com.example.starter.domain.ValveSeal).count();
        // 竞争唯一：复封与调节互斥，至多一方生效；无论谁先，最终状态一致
        assertThat(resealSucceeded).isLessThanOrEqualTo(1);
        assertThat(adjustSucceeded).isLessThanOrEqualTo(1);
        if (resealSucceeded == 1) {
            // 复封先生效：不可能再追加调节版本
            assertThat(countOf("valve_opening_version")).isZero();
            assertThat(sealService.getPermit(permit.id()).status())
                    .isEqualTo(UnsealPermit.PermitStatus.CLOSED);
            assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        } else {
            // 调节先生效：复封因版本已变化被拒绝，版本链恰一条
            assertThat(adjustSucceeded).isEqualTo(1);
            assertThat(countOf("valve_opening_version")).isEqualTo(1);
            assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        }
    }

    @Test
    void 同键复封并发只终结一次() throws Exception {
        UnsealPermit permit = sealAndApprove("SEAL-210");

        List<Outcome> outcomes = runConcurrently(3,
                i -> sealService.reseal(new SealService.ResealCommand(
                        "req-reseal-same", "SEAL-210", 0, "SEAL-211", "赵六", "孙七")));

        long succeeded = outcomes.stream().filter(o -> o.result() != null).count();
        // 同键同参：首个成功，其余重放首次结果
        assertThat(succeeded).isEqualTo(3);
        long distinctSeals = outcomes.stream()
                .map(o -> ((com.example.starter.domain.ValveSeal) o.result()).id()).distinct().count();
        assertThat(distinctSeals).isEqualTo(1);
        assertThat(countOf("valve_seal")).isEqualTo(2);
        assertThat(sealService.getPermit(permit.id()).status())
                .isEqualTo(UnsealPermit.PermitStatus.CLOSED);
    }
}
