package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.dto.ApplyRequest;

/**
 * 单车同时刻唯一在途运单的真实并发边界：多线程用不同 requestId 同时为同一辆车申请，
 * 数据库占用表主键约束保证恰有一个成功。
 */
class ConcurrentApplyTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    @Test
    void concurrentAppliesForSameVehicleExactlyOneSucceeds() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger busyRejected = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        service.apply(new ApplyRequest("cc-apply-" + idx, "沪A-1001",
                                "TOXIC", "R-G1", DEPARTURE));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if ("VEHICLE_BUSY".equals(e.getCode())) {
                            busyRejected.incrementAndGet();
                        } else {
                            otherErrors.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(busyRejected.get()).isEqualTo(threads - 1);
        assertThat(otherErrors.get()).isZero();
        // 终态数据自洽：占用表恰有一行；该车辆 APPLIED 运单恰一条，败者事务已回滚无残留。
        assertThat(repository.findActiveShipment("沪A-1001")).isPresent();
        Integer appliedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment WHERE vehicle_plate = ? AND status = 'APPLIED'",
                Integer.class, "沪A-1001");
        assertThat(appliedCount).isEqualTo(1);
    }
}
