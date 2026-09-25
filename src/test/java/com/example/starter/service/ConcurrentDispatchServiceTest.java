package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 真实并发测试：同一工位同时刻并发派工，唯一受理（工位守卫全库唯一约束兜底）。
 */
class ConcurrentDispatchServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService service;

    @Test
    @DisplayName("同一工位 8 个线程并发派工不同工序卡：恰 1 张受理，其余 409，最终仅 1 张执行中卡")
    void concurrentDispatchSingleAcceptance() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        Outcome outcome = service.dispatch(TestRequests.singlePoint(
                                "req-conc-" + index, "CARD-C" + index, "STATION-X", "N",
                                "P1", "100.000"));
                        if (outcome.status() == 201) {
                            accepted.incrementAndGet();
                        } else if (outcome.status() == 409) {
                            rejected.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(other.get()).isZero();
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        Integer guardCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_guard WHERE station = ?", Integer.class, "STATION-X");
        assertThat(guardCount).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 requestId 并发重复派工：只产生一张卡，两个调用都返回首次 201")
    void concurrentSameRequestId() throws InterruptedException {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        // 同键同参（不同工位也无所谓，幂等台账唯一约束决定结果）
                        Outcome outcome = service.dispatch(TestRequests.singlePoint(
                                "req-same-key", "CARD-DUP", "STATION-Y", "N", "P1", "100.000"));
                        if (outcome.status() == 201) {
                            ok.incrementAndGet();
                        } else if (outcome.status() == 409) {
                            conflict.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(other.get()).isZero();
        assertThat(conflict.get()).isZero();
        assertThat(ok.get()).isEqualTo(threads);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_card WHERE card_no = ?", Integer.class, "CARD-DUP"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_guard WHERE station = ?", Integer.class, "STATION-Y"))
                .isEqualTo(1);
    }
}
