package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaEvent;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铅封并发边界：复封与调节竞争唯一、同键并发幂等、并发登记唯一。
 */
class SealConcurrencyTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;
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

    private UnsealOrder sealedAndApproved(int quota) {
        sealService.registerSeal(1L, "req-seal-1", "SEAL-0001", 0, "张三");
        return sealService.approveUnseal(1L, "req-unseal-1", "SEAL-0001", 0, "李四", "王五",
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(10)), quota);
    }

    @Test
    void 复封与调节竞争唯一不可已复封仍追加调节版本() throws Exception {
        UnsealOrder order = sealedAndApproved(2);
        submitReadings(40.00, 42.50, 41.00, 41.00);

        List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                ? adjustmentService.issueAdjustment(STATION_ID, "req-adj-race", 1L,
                        new BigDecimal("5"), 0)
                : sealService.reseal(1L, "req-reseal-race", "SEAL-0002", 0, "赵六", "孙七"));

        Outcome adjust = outcomes.get(0);
        Outcome reseal = outcomes.get(1);
        // 复封总是成功：原子终结旧解封权限并登记新铅封
        assertThat(reseal.error()).isNull();
        ValveSeal renewed = (ValveSeal) reseal.result();
        assertThat(renewed.sealNo()).isEqualTo("SEAL-0002");
        assertThat(sealService.getUnsealOrder(order.id()).status())
                .isEqualTo(UnsealOrder.OrderStatus.TERMINATED);

        if (adjust.error() == null) {
            // 调节先于复封提交：恰消费一次额度、追加一个版本
            assertThat(((AdjustmentTask) adjust.result()).status())
                    .isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
            assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
            assertThat(countOf("valve_opening_version")).isEqualTo(1);
        } else {
            // 复封先于调节重查：调节被拒绝，不消费、不追加版本
            assertThat(adjust.error()).isInstanceOf(UnprocessableException.class);
            assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
            assertThat(countOf("valve_opening_version")).isZero();
        }

        // 复封后无论竞争结果如何，都不可再追加调节版本
        int versionsAfterRace = countOf("valve_opening_version");
        int currentVersion = adjustmentService.getValveVersions(1L).size();
        submitReadings(40.00, 42.50, 41.00, 41.00);
        // 复封后可能仍有执行中任务（调节先成功的情况），先评估归档释放占位
        if (adjust.error() == null) {
            clock.advance(Duration.ofHours(4));
            adjustmentService.assess(((AdjustmentTask) adjust.result()).id(), "req-asm-race");
            adjustmentService.archive(((AdjustmentTask) adjust.result()).id());
        }
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-after-reseal", 1L, new BigDecimal("5"), currentVersion))
                .isInstanceOf(UnprocessableException.class);
        assertThat(countOf("valve_opening_version")).isEqualTo(versionsAfterRace);
    }

    @Test
    void 同键并发批准解封唯一受理且结果一致() throws Exception {
        sealService.registerSeal(1L, "req-seal-1", "SEAL-0001", 0, "张三");

        List<Outcome> outcomes = runConcurrently(4,
                i -> sealService.approveUnseal(1L, "req-unseal-same", "SEAL-0001", 0, "李四", "王五",
                        T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(10)), 2));

        assertThat(outcomes).allMatch(o -> o.error() == null);
        long distinctOrderIds = outcomes.stream()
                .map(o -> ((UnsealOrder) o.result()).id()).distinct().count();
        assertThat(distinctOrderIds).isEqualTo(1);
        assertThat(countOf("unseal_order")).isEqualTo(1);
        // 授予事件只写一次
        assertThat(countOf("unseal_quota_event")).isEqualTo(1);
    }

    @Test
    void 同键并发调节不重复消费额度() throws Exception {
        UnsealOrder order = sealedAndApproved(2);
        submitReadings(40.00, 42.50, 41.00, 41.00);

        List<Outcome> outcomes = runConcurrently(4,
                i -> adjustmentService.issueAdjustment(STATION_ID, "req-adj-same", 1L,
                        new BigDecimal("5"), 0));

        assertThat(outcomes).allMatch(o -> o.error() == null);
        long distinctTaskIds = outcomes.stream()
                .map(o -> ((AdjustmentTask) o.result()).id()).distinct().count();
        assertThat(distinctTaskIds).isEqualTo(1);
        // 额度只消费一次，开度只变更一次
        assertThat(sealService.getUnsealOrder(order.id()).quotaUsed()).isEqualTo(1);
        assertThat(sealService.getQuotaEvents(order.id()))
                .extracting(UnsealQuotaEvent::eventType)
                .containsExactly(UnsealQuotaEvent.EventType.GRANT,
                        UnsealQuotaEvent.EventType.CONSUME);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 并发登记同一阀门铅封唯一() throws Exception {
        List<Outcome> outcomes = runConcurrently(4,
                i -> sealService.registerSeal(1L, "req-seal-c-" + i, "SEAL-C-" + i, 0, "张三"));

        long succeeded = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicted = outcomes.stream()
                .filter(o -> o.error() instanceof ConflictException).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(3);
        assertThat(countOf("valve_seal")).isEqualTo(1);
    }
}
