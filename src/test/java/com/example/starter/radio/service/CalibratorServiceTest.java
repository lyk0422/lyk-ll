package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.radio.model.DailyCheck;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 活度计日检：恰 5% 合法可用、超过 5% 当日禁用、requestId 幂等、并发唯一受理。
 */
class CalibratorServiceTest extends IntegrationTestBase {

    @Autowired
    private CalibratorService calibratorService;

    @Autowired
    private RegistrationService registrationService;

    @Test
    void exactlyFivePercentDeviationPasses() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        DailyCheck check = calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE,
                1000.0, 1050.0);
        assertThat(check.deviationRatio()).isEqualTo(0.05);
        assertThat(check.passed()).isTrue();
    }

    @Test
    void overFivePercentDeviationFailsAndDisablesForTheDay() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        DailyCheck check = calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE,
                1000.0, 1051.0);
        assertThat(check.deviationRatio()).isEqualTo(0.051);
        assertThat(check.passed()).isFalse();
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        String requestId = unique("REQ");
        DailyCheck first = calibratorService.dailyCheck(requestId, calibratorId, CHECK_DATE, 1000.0, 1050.0);
        DailyCheck replay = calibratorService.dailyCheck(requestId, calibratorId, CHECK_DATE, 1000.0, 1050.0);
        assertThat(replay.id()).isEqualTo(first.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibrator_daily_check WHERE request_id = ?", Integer.class, requestId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        String requestId = unique("REQ");
        calibratorService.dailyCheck(requestId, calibratorId, CHECK_DATE, 1000.0, 1050.0);
        assertThatThrownBy(() -> calibratorService.dailyCheck(requestId, calibratorId, CHECK_DATE,
                1000.0, 1060.0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void secondSessionSameDaySameCalibratorRejected() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
        assertThatThrownBy(() -> calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE,
                1000.0, 1001.0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void concurrentDailyChecksOnlyOneAccepted() throws Exception {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        java.util.List<String> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try {
            Runnable task = () -> {
                await(gate);
                try {
                    calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
                    outcomes.add("ACCEPTED");
                } catch (ConflictException e) {
                    outcomes.add("CONFLICT");
                }
            };
            Future<?> f1 = pool.submit(task);
            Future<?> f2 = pool.submit(task);
            gate.countDown();
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outcomes).containsExactlyInAnyOrder("ACCEPTED", "CONFLICT");
        Integer sessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibrator_daily_check WHERE calibrator_id = ? AND check_date = ?",
                Integer.class, calibratorId, java.sql.Date.valueOf(CHECK_DATE));
        assertThat(sessions).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
