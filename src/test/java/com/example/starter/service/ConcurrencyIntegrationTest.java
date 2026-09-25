package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.starter.AbstractDivingIntegrationTest;
import com.example.starter.api.ApplicationRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.SegmentResponse;
import com.example.starter.support.BusinessException;
import com.example.starter.service.DivingService.ServiceResult;

/**
 * 真实多线程并发的 H2 事务测试：
 * 同组同时刻并发申请唯一受理；两人并发提交同一配对槽唯一成组；同 requestId 并发只受理一次。
 */
class ConcurrencyIntegrationTest extends AbstractDivingIntegrationTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    private interface Task {
        void run() throws Exception;
    }

    /** 两个线程在同一屏障后同时启动，返回成功数与首个业务错误码；带 30 秒超时防死锁。 */
    private Outcome runConcurrently(Task t1, Task t2) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        java.util.List<String> errorCodes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable r1 = () -> {
            ready.countDown();
            await(start);
            try {
                t1.run();
                success.incrementAndGet();
            } catch (BusinessException ex) {
                errorCodes.add(ex.getCode());
            } catch (Exception ex) {
                errorCodes.add(ex.getClass().getSimpleName() + ":" + ex.getMessage());
            }
        };
        Runnable r2 = () -> {
            ready.countDown();
            await(start);
            try {
                t2.run();
                success.incrementAndGet();
            } catch (BusinessException ex) {
                errorCodes.add(ex.getCode());
            } catch (Exception ex) {
                errorCodes.add(ex.getClass().getSimpleName() + ":" + ex.getMessage());
            }
        };
        Future<?> f1 = pool.submit(r1);
        Future<?> f2 = pool.submit(r2);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        return new Outcome(success.get(), errorCodes);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private record Outcome(int success, java.util.List<String> errorCodes) {
    }

    @Test
    void concurrentApplicationsForSameGroupOnlyOneAccepted() throws Exception {
        Task t1 = () -> service.apply(new ApplicationRequest(
                "cc-app-1", "diver-a", "diver-b", 18, 50));
        Task t2 = () -> service.apply(new ApplicationRequest(
                "cc-app-2", "diver-a", "diver-b", 20, 40));
        Outcome outcome = runConcurrently(t1, t2);

        assertThat(outcome.success()).isEqualTo(1);
        assertThat(outcome.errorCodes()).containsExactly("GROUP_BUSY");
        // 唯一受理：仅一个潜次落库、占组一行。
        assertThat(count("dive_operation", "")).isEqualTo(1L);
        assertThat(count("active_dive_group", "")).isEqualTo(1L);
        // 失败事务回滚，不残留方案与占组孤儿。
        assertThat(count("dive_plan", "")).isEqualTo(1L);
    }

    @Test
    void concurrentPairSubmissionsCreateExactlyOnePairGroup() throws Exception {
        PlanResponse plan = service.apply(
                application("cc-pair-app", "diver-a", "diver-b", 18, 56)).body();
        String opNo = plan.operationNo();

        Task t1 = () -> service.recordSegment(
                descendSegment("cc-pair-a", opNo, "diver-a", 0, 18, 0, 60));
        Task t2 = () -> service.recordSegment(
                descendSegment("cc-pair-b", opNo, "diver-b", 0, 18, 0, 60));
        Outcome outcome = runConcurrently(t1, t2);

        assertThat(outcome.success()).isEqualTo(2);
        // 最终两条记录均 EFFECTIVE，唯一配对组，台账两行，无重复成组。
        assertThat(count("dive_segment", "WHERE pair_state = 'EFFECTIVE'")).isEqualTo(2L);
        assertThat(count("dive_segment", "WHERE pair_state = 'PENDING'")).isZero();
        assertThat(count("segment_pair", "")).isEqualTo(1L);
        assertThat(count("diver_time_ledger", "")).isEqualTo(2L);
    }

    @Test
    void concurrentSameRequestIdProcessedExactlyOnce() throws Exception {
        // 两个线程使用完全相同的 requestId 与参数并发申请：只产生一个潜次，另一个幂等重放。
        Task t1 = () -> service.apply(new ApplicationRequest(
                "cc-same-id", "diver-x", "diver-y", 18, 56));
        Task t2 = () -> service.apply(new ApplicationRequest(
                "cc-same-id", "diver-x", "diver-y", 18, 56));
        Outcome outcome = runConcurrently(t1, t2);

        assertThat(outcome.success()).isEqualTo(2);
        assertThat(outcome.errorCodes()).isEmpty();
        assertThat(count("dive_operation", "")).isEqualTo(1L);
        assertThat(count("idempotent_request", "")).isEqualTo(1L);
        assertThat(count("active_dive_group", "")).isEqualTo(1L);
    }
}
