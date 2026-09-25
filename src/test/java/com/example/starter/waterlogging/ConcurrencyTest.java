package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.waterlogging.api.PumpCommandRequest;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.service.WaterLoggingService;

/**
 * 真实并发边界（H2 行锁/唯一约束）：
 * 同一 requestId 预警并发仅一方首办、其余重放，泵站只联动一次；
 * 同站同时刻启停指令并发唯一受理。
 */
class ConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Autowired
    private WaterLoggingService service;

    @Test
    void concurrentSameRequestIdWarningStartsPreDrainExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.startOnWarning(new WarningStartRequest(
                            "WARN-CONCURRENT-1", "ORANGE", "DONGWAN", "东湾河",
                            new BigDecimal("3.00"), T0, new BigDecimal("60.00")));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AtomicInteger firstCount = new AtomicInteger();
            AtomicInteger replayCount = new AtomicInteger();
            AtomicInteger failureCount = new AtomicInteger();
            Long firstPlanId = null;
            for (Future<?> future : futures) {
                try {
                    Object response = future.get(30, TimeUnit.SECONDS);
                    var idempotent = (com.example.starter.waterlogging.web.IdempotentResponse<?>) response;
                    if (idempotent.replay()) {
                        replayCount.incrementAndGet();
                    } else {
                        firstCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                }
            }

            assertThat(firstCount.get()).isEqualTo(1);
            assertThat(replayCount.get()).isEqualTo(threads - 1);
            assertThat(failureCount.get()).isZero();
            // 预降单、分区锁、两站 START 台账均只有一份。
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pre_drain_plan WHERE request_id = 'WARN-CONCURRENT-1'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM zone_sequence_lock WHERE basin_zone = 'DONGWAN'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pump_command WHERE action = 'START'", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pump_station WHERE run_status = 'RUNNING'", Integer.class))
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentCommandsForSameStationAndTimeAcceptOnlyOne() throws Exception {
        service.startOnWarning(new WarningStartRequest(
                "WARN-CONCURRENT-2", "ORANGE", "DONGWAN", "东湾河",
                new BigDecimal("3.00"), T0, new BigDecimal("60.00")));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        LocalDateTime commandTime = T0.plusMinutes(5);

        try {
            for (int i = 0; i < threads; i++) {
                String requestId = "CMD-CONCURRENT-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.commandPump(new PumpCommandRequest(
                            requestId, "DW-02", PumpAction.STOP, commandTime, BigDecimal.ZERO));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    success.incrementAndGet();
                } catch (Exception ex) {
                    // 唯一约束/业务唯一受理均折叠为 409 语义。
                    boolean isConflict = ex.getCause() instanceof ConflictException;
                    if (isConflict) {
                        conflict.incrementAndGet();
                    }
                }
            }

            assertThat(success.get()).isEqualTo(1);
            assertThat(conflict.get()).isEqualTo(threads - 1);
            Integer commandCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pump_command
                    WHERE station_code = 'DW-02' AND command_time = ?
                    """, Integer.class, java.sql.Timestamp.valueOf(commandTime));
            assertThat(commandCount).isEqualTo(1);
            // 唯一受理的指令确实执行：DW-02 停机、闸门全关。
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT run_status FROM pump_station WHERE station_code = 'DW-02'", String.class))
                    .isEqualTo(PumpAction.STOPPED);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
