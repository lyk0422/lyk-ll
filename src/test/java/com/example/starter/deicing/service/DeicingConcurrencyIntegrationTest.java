package com.example.starter.deicing.service;

import com.example.starter.deicing.AbstractDeicingIntegrationTest;
import com.example.starter.deicing.api.ApplyRequest;
import com.example.starter.deicing.exception.ApiException;
import com.example.starter.deicing.model.DeiceType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界 H2 测试：同一 requestId / 同一航班并发申请，以及同一申请并发出队，
 * 必须由数据库唯一约束与抢占式幂等台账保证唯一受理，不得产生重复记录或重复占用。
 */
class DeicingConcurrencyIntegrationTest extends AbstractDeicingIntegrationTest {

    /**
     * 在 lambda 内等待发令闩释放，超时断言失败；屏蔽受检异常以适配 Runnable 式提交。
     *
     * @param latch 发令闩
     */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("并发发令闩等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待发令闩被中断", e);
        }
    }

    @Test
    void concurrentApplicationsWithSameRequestIdAcceptedExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger concurrentRejected = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            Future<?>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    awaitLatch(start);
                    ApplyRequest request = new ApplyRequest(
                            "CONC-1", "CA1001", "S1", DeiceType.TWO_STEP, 1, BASE + 120 * MIN);
                    try {
                        DeicingService.IdentifiedResult result = service.apply(request);
                        // 首次受理与提交后的幂等重放均返回 201
                        assertThat(result.httpStatus()).isEqualTo(201);
                        accepted.incrementAndGet();
                    } catch (ApiException e) {
                        if ("CONCURRENT_OPERATION".equals(e.getErrorCode())) {
                            concurrentRejected.incrementAndGet();
                        } else {
                            conflicts.incrementAndGet();
                        }
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 唯一受理：仅一条排队记录、一条进行中航班登记；无“异参冲突”等意外错误
        assertThat(countOf("queue_request")).isEqualTo(1L);
        assertThat(countOf("flight_active")).isEqualTo(1L);
        assertThat(conflicts.get()).isZero();
        assertThat(accepted.get() + concurrentRejected.get()).isEqualTo(threads);
        assertThat(accepted.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentApplicationsForSameFlightWithDifferentRequestIdsOnlyOneWins() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            Future<?>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    awaitLatch(start);
                    ApplyRequest request = new ApplyRequest(
                            "CONC-F" + index, "CA2002", "S1", DeiceType.TWO_STEP, 1,
                            BASE + 120 * MIN);
                    try {
                        service.apply(request);
                        created.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.getErrorCode()).isEqualTo("FLIGHT_ALREADY_ACTIVE");
                        rejected.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(created.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(countOf("queue_request")).isEqualTo(1L);
        assertThat(countOf("flight_active")).isEqualTo(1L);
    }

    @Test
    void concurrentDispatchOfSameRequestBooksOccupancyOnce() throws Exception {
        service.apply(new ApplyRequest(
                "CONC-D", "CA3003", "S1", DeiceType.TWO_STEP, 1, BASE + 120 * MIN));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            Future<?>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    awaitLatch(start);
                    try {
                        DeicingService.IdentifiedResult result = service.dispatch("CONC-D");
                        assertThat(result.view().status().name()).isIn("SCHEDULED");
                        succeeded.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.getErrorCode()).isEqualTo("CONCURRENT_OPERATION");
                        rejected.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 出队确认唯一受理；占用只排定一次
        assertThat(succeeded.get()).isGreaterThanOrEqualTo(1);
        assertThat(succeeded.get() + rejected.get()).isEqualTo(threads);
        assertThat(countOf("deicing_assignment")).isEqualTo(1L);
    }
}
