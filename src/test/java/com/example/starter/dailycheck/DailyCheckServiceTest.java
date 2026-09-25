package com.example.starter.dailycheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.calibrator.CalibratorService;
import com.example.starter.error.ApiExceptionHandler.IdempotencyConflictException;
import com.example.starter.error.ApiExceptionHandler.StateConflictException;
import com.example.starter.support.BaseIntegrationTest;

/**
 * 活度计日检集成测试（H2）：±5% 边界、幂等与并发唯一受理。
 */
class DailyCheckServiceTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    /** 参考源标定时刻早于日检一个半衰期：期望活度恰为标定值一半。 */
    private static final Instant CHECKED_AT = Instant.parse("2026-09-26T09:00:00Z");

    @Autowired
    private DailyCheckService dailyCheckService;
    @Autowired
    private CalibratorService calibratorService;

    private long calibratorId;
    private long referenceSourceId;

    @BeforeEach
    void setUp() {
        calibratorId = calibratorService.registerCalibrator("CAL-01", "活度计一号").id();
        // 标定活度 200 MBq @ T0，TEST-1H 半衰期 1h，日检时刻期望活度 100 MBq
        referenceSourceId = calibratorService.registerReferenceSource(calibratorId, testNuclideId(),
                200.0, T0).id();
    }

    @Test
    void 日检偏差恰为正5百分比合法可用() {
        var view = dailyCheckService.check("dc-req-1", calibratorId, referenceSourceId, 105.0, CHECKED_AT);
        assertThat(view.expectedActivityMbq()).isEqualTo(100.0);
        assertThat(view.deviation()).isEqualTo(0.05);
        assertThat(view.passed()).isTrue();
        assertThat(dailyCheckService.isUsable(calibratorId, LocalDate.parse("2026-09-26"))).isTrue();
    }

    @Test
    void 日检偏差恰为负5百分比合法可用() {
        var view = dailyCheckService.check("dc-req-2", calibratorId, referenceSourceId, 95.0, CHECKED_AT);
        assertThat(view.deviation()).isEqualTo(-0.05);
        assertThat(view.passed()).isTrue();
    }

    @Test
    void 日检偏差超过5百分比当日禁用() {
        var view = dailyCheckService.check("dc-req-3", calibratorId, referenceSourceId, 105.001,
                CHECKED_AT);
        assertThat(view.passed()).isFalse();
        assertThat(dailyCheckService.isUsable(calibratorId, LocalDate.parse("2026-09-26"))).isFalse();
    }

    @Test
    void 同键同参重放返回首次结果() {
        var first = dailyCheckService.check("dc-req-4", calibratorId, referenceSourceId, 100.0,
                CHECKED_AT);
        var replay = dailyCheckService.check("dc-req-4", calibratorId, referenceSourceId, 100.0,
                CHECKED_AT);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM daily_check").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 同键异参返回幂等冲突() {
        dailyCheckService.check("dc-req-5", calibratorId, referenceSourceId, 100.0, CHECKED_AT);
        assertThatThrownBy(() -> dailyCheckService.check("dc-req-5", calibratorId, referenceSourceId,
                101.0, CHECKED_AT))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void 同一活度计当日第二次日检被拒绝() {
        dailyCheckService.check("dc-req-6a", calibratorId, referenceSourceId, 100.0, CHECKED_AT);
        assertThatThrownBy(() -> dailyCheckService.check("dc-req-6b", calibratorId, referenceSourceId,
                100.0, CHECKED_AT))
                .isInstanceOf(StateConflictException.class);
    }

    @Test
    void 并发日检同一活度计唯一受理() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        try {
                            dailyCheckService.check("dc-concurrent-" + i, calibratorId,
                                    referenceSourceId, 100.0, CHECKED_AT);
                            return "OK";
                        } catch (StateConflictException e) {
                            return "CONFLICT";
                        }
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new java.util.ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn("OK"::equals).hasSize(1);
            assertThat(results).filteredOn("CONFLICT"::equals).hasSize(threads - 1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM daily_check WHERE calibrator_id = ?")
                    .param(calibratorId).query(Long.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
