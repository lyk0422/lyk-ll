package com.example.starter.pump.business;

import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.testsupport.TaskInsertProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接验证 H2 唯一索引 uk_station_time：多线程并发插入同站同时刻任务，仅一个成功。
 */
class StationTimeUniqueConstraintTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private TaskInsertProbe probe;

    @Test
    void concurrentInsertSameStationAndTime_onlyOneSucceeds() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Future<?>[] futures = new Future[threads];

        try {
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    // 无外键约束，直接用固定ID并发插入同一(站点,时刻)
                    Long taskId = probe.insertInNewTransaction(999L, 999L, "PS-01", T0);
                    if (taskId == null) {
                        rejected.incrementAndGet();
                    } else {
                        success.incrementAndGet();
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(count("pump_task")).isEqualTo(1);
    }
}
