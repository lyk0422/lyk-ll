package com.example.starter.service;

import com.example.starter.AbstractBloodIntegrationTest;
import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.ComponentType;
import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.example.starter.web.dto.TestResultRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复检合格回传与过期判定并发竞态：以时间先后形成唯一终态，
 * 不得出现既放行又报废的矛盾状态。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class BloodServiceRaceTest extends AbstractBloodIntegrationTest {

    private static final Instant WINDOW_START = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-10T00:00:00Z");

    @Autowired
    private BloodService bloodService;

    @Test
    void repeatPassReport_versusExpiryJudgement_convergesToSingleTerminalState() throws Exception {
        int rounds = 6;
        int releasedRounds = 0;
        int expiredRounds = 0;

        for (int round = 0; round < rounds; round++) {
            final int roundNo = round;
            String barcode = String.format("C%04d", roundNo);
            // 效期边界 T0：边界时刻（含）回传/放行合法，T0+1s 触发过期判定
            Instant boundary = Instant.parse("2026-09-30T15:59:59Z");
            bloodService.intake(new IntakeRequest(barcode, ComponentType.RED_CELL, boundary));
            bloodService.reportTestResult(
                    new TestResultRequest(barcode, TestType.INITIAL, TestResultCode.PASS, null));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            // 线程 A：回传复检合格（恰在边界时刻）后立刻请求放行
            pool.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                bloodService.reportTestResult(
                        new TestResultRequest(barcode, TestType.REPEAT, TestResultCode.PASS, boundary));
                bloodService.release(new ReleaseRequest("REQ-A-" + roundNo, barcode,
                        WINDOW_START, WINDOW_END, boundary));
                return null;
            });

            // 线程 B：在边界之后 1 秒发起放行，触发过期判定
            pool.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                ReleaseResponse r = bloodService.release(new ReleaseRequest("REQ-B-" + roundNo, barcode,
                        WINDOW_START, WINDOW_END, boundary.plusSeconds(1)));
                return r;
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            BloodUnit finalUnit = bloodService.getUnit(barcode);

            // 核心不变量：唯一终态，且库存与发血记录严格一致
            if (finalUnit.getStatus() == UnitStatus.RELEASED) {
                releasedRounds++;
                assertThat(finalUnit.getExpiredAt()).isNull();
                var issue = issueRepository.findByBarcode(barcode);
                assertThat(issue).isPresent();
                assertThat(issue.orElseThrow().getRequestId()).startsWith("REQ-A-");
            } else {
                assertThat(finalUnit.getStatus()).isEqualTo(UnitStatus.EXPIRED);
                expiredRounds++;
                assertThat(finalUnit.getExpiredAt()).isEqualTo(boundary.plusSeconds(1));
                assertThat(issueRepository.findByBarcode(barcode)).isEmpty();
            }
        }

        // 多轮真实并发后，每一轮都只产生一个终态（既放行又报废绝不允许）
        assertThat(releasedRounds + expiredRounds).isEqualTo(rounds);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
