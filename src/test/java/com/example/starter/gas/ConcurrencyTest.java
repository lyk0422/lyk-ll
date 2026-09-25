package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.IdempotencyConflictException;
import com.example.starter.gas.web.TaskSlotConflictException;
import com.example.starter.gas.web.dto.LinkageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实并发下的唯一受理与幂等边界（使用真实线程 + H2 唯一约束，不用 mock/Map 替代）。
 */
@Import(FixedClockConfig.class)
class ConcurrencyTest extends AbstractGasIntegrationTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private GasStationService service;

    @Test
    void concurrentLinkagesAtSameStationAndTimeHaveSingleAcceptance() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.regulate(new LinkageRequest("LC-" + idx, STATION, T,
                                new BigDecimal("180"), new BigDecimal("100"), null));
                        accepted.incrementAndGet();
                    } catch (TaskSlotConflictException ex) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 恰一个受理，其余全部冲突
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        // 最终数据只有一条任务与一个受理槽
        assertThat(countOf("linkage_slot", "station_code = ?", STATION)).isEqualTo(1);
        assertThat(countOf("linkage_task", "station_code = ?", STATION)).isEqualTo(1);
        assertThat(countOf("pressure_detection", "station_code = ?", STATION)).isEqualTo(1);
        // 落选请求的幂等占位已随回滚清理
        assertThat(countOf("idempotent_request", "1=1")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeySameParamsYieldSingleEffectAndReplayResult() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    service.regulate(new LinkageRequest("SAME-KEY", STATION, T.plusMinutes(2),
                            new BigDecimal("180"), new BigDecimal("100"), null));
                    ok.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 一个首发，其余同键同参重放，均成功返回
        assertThat(ok.get()).isEqualTo(threads);
        assertThat(countOf("linkage_task", "request_id = ?", "SAME-KEY")).isEqualTo(1);
        assertThat(countOf("pressure_detection", "request_id = ?", "SAME-KEY")).isEqualTo(1);
        assertThat(countOf("idempotent_request", "request_id = ? AND response_body IS NOT NULL",
                "SAME-KEY")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyDifferentParamsNeverCorruptsState() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            futures.add(pool.submit(() -> {
                start.await();
                service.regulate(new LinkageRequest("RACE", STATION, T.plusMinutes(7),
                        new BigDecimal("180"), new BigDecimal("100"), null));
                ok.incrementAndGet();
                return null;
            }));
            futures.add(pool.submit(() -> {
                start.await();
                // 同键不同压力
                service.regulate(new LinkageRequest("RACE", STATION, T.plusMinutes(7),
                        new BigDecimal("190"), new BigDecimal("100"), null));
                ok.incrementAndGet();
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    if (ex.getCause() instanceof IdempotencyConflictException) {
                        conflict.incrementAndGet();
                    } else {
                        throw ex;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(countOf("linkage_task", "request_id = ?", "RACE")).isEqualTo(1);
    }
}
