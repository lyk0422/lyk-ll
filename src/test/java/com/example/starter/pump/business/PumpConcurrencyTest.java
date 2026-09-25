package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.WarningRequest;
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
 * 并发边界（真实多线程 + H2 唯一约束）：同一泵站同时刻并发启停指令唯一受理；
 * 同一分区同时刻不同站归入同一套联动序列。
 */
class PumpConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    @Test
    void sameStationSameTime_concurrentCommands_onlyOneAccepted() throws Exception {
        Long id = facade.handleWarning(new WarningRequest("w-c", "ZN", "ORANGE", T0)).pumpdownId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Future<?>[] futures = new Future[threads];

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        facade.handlePumpCommand(new PumpCommandRequest(
                                "tc-" + idx, id, "PS-01", "START", T0, null));
                        accepted.incrementAndGet();
                    } catch (ApiException ex) {
                        // READ_COMMITTED 下先提交者对其余线程可见：表现为序列/站点冲突或泵已运行，均属唯一受理
                        if ("STATION_TIME_CONFLICT".equals(ex.getCode())
                                || "ZONE_SEQUENCE_CONFLICT".equals(ex.getCode())
                                || "PUMP_ALREADY_RUNNING".equals(ex.getCode())) {
                            conflict.incrementAndGet();
                        } else {
                            throw new RuntimeException(ex);
                        }
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        // 唯一受理：仅一条启停任务、一条闸门记录；7 条冲突指令整事务回滚。
        // 幂等台账保留 2 条：1 条预警 + 1 条唯一受理的启泵指令。
        assertThat(count("pump_task")).isEqualTo(1);
        assertThat(count("gate_opening_log")).isEqualTo(1);
        assertThat(count("idempotent_request")).isEqualTo(2);
    }

    @Test
    void sameZoneSameTime_differentStations_shareOneSequence() {
        Long id = facade.handleWarning(new WarningRequest("w-z", "ZN", "ORANGE", T0)).pumpdownId();

        var ps01 = facade.handlePumpCommand(new PumpCommandRequest(
                "tz-1", id, "PS-01", "START", T0, null));
        var ps02 = facade.handlePumpCommand(new PumpCommandRequest(
                "tz-2", id, "PS-02", "START", T0, null));

        // 同分区同时刻只允许一套序列：两站归入同一 sequenceId
        assertThat(ps02.sequenceId()).isEqualTo(ps01.sequenceId());
        assertThat(count("pump_sequence")).isEqualTo(1);
        assertThat(count("pump_task")).isEqualTo(2);
    }
}
