package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsLedgerView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.time.RaceBusinessClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关 H2 真实并发测试：
 * 1) 余量竞争：多子批并发预占，恰满足容量者成功，其余失败，绝不超占；
 * 2) 撤销与签发竞争：按提交顺序唯一，不得既核销又释放，落败方被正确阻断/保留证据。
 */
@Import(CustomsConcurrencyTest.RaceClockConfig.class)
class CustomsConcurrencyTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class RaceClockConfig {
        @Bean
        @Primary
        RaceBusinessClock raceBusinessClock() {
            return new RaceBusinessClock();
        }
    }

    @Autowired
    private RaceBusinessClock raceClock;

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant REVOKE_AT = Instant.parse("2026-09-08T01:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");

    @Test
    void concurrentBindsNeverExceedTotalAndExactlyCapacityWins() throws Exception {
        raceClock.setFallback(T0);
        customsService.register(new CustomsRegisterRequest("c-reg", "CI-CONC",
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, VALID_UNTIL, 100, T0));

        int threads = 10;
        int each = 20; // 恰 5 个成功占满 100
        for (int i = 0; i < threads; i++) {
            prepareIssueableImportBatch("c-d-" + i, "B-CONC-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                // 读-改-写：携带最新 expectedVersion；遇并发版本冲突刷新后重试，
                // 最终只在容量不足时收到 422，验证绝不超占而非乐观锁本身。
                for (int attempt = 0; attempt < 50; attempt++) {
                    int expectedVersion = customsService.getLedger("CI-CONC").version();
                    try {
                        customsService.bind(new CustomsBindRequest(
                                "c-bind-" + index, "CI-CONC", "B-CONC-" + index,
                                each, expectedVersion, T0));
                        return 200;
                    } catch (BusinessException ex) {
                        if (ex.getHttpStatus() == 409 && "VERSION_CONFLICT".equals(ex.getError())) {
                            continue;
                        }
                        return ex.getHttpStatus();
                    }
                }
                return 500;
            }));
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long ok = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.stream().filter(s -> s == 422).count();
        assertEquals(5, ok, "100 支容量、每批 20 支，恰 5 笔成功");
        assertEquals(5, rejected, "其余 5 笔必须超占 422");
        CustomsLedgerView ledger = customsService.getLedger("CI-CONC");
        assertEquals(100, ledger.reservedQuantity(), "并发预占绝不超过总数");
        assertEquals(0, ledger.availableQuantity());
        assertEquals(5, ledger.bindings().stream().filter(b -> b.status().equals("RESERVED")).count());
    }

    @Test
    void revokeAndIssueRaceHasExactlyOneWinnerAcrossRounds() throws Exception {
        for (int round = 0; round < 10; round++) {
            final String customsNo = "CI-RACE-" + round;
            final String batchNo = "B-RACE-" + round;
            final int r = round;

            // 申报+评定发生在 T0（公示 09-01→09-08 UTC），再把主时钟推进到 ISSUE_AT 令公示期满生效。
            raceClock.setFallback(T0);
            customsService.register(new CustomsRegisterRequest("race-reg-" + round, customsNo,
                    IMPORT_ENTERPRISE, IMPORT_PRODUCT, VALID_UNTIL, 100, T0));
            prepareIssueableImportBatch("race-d-" + round, batchNo);
            raceClock.setFallback(ISSUE_AT);
            reviewService.settlePublicity(batchNo);
            customsService.bind(new CustomsBindRequest("race-bind-" + round, customsNo, batchNo, 30, 1, T0));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);

            Future<Outcome> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                ServiceResult result = reviewService.issue(batchNo, new IssueRequest("race-issue-" + r));
                return result.httpStatus() == 200 ? Outcome.ISSUED : Outcome.BLOCKED;
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return customsService.revoke(
                            new CustomsRevokeRequest("race-revoke-" + r, customsNo, 2, REVOKE_AT)).httpStatus();
                } catch (BusinessException ex) {
                    // 签发先提交导致版本前进时，撤销以 409 异常落败（写操作失败回滚不占键）。
                    return ex.getHttpStatus();
                }
            });

            Outcome issueOutcome = issueFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            // 两事务在 customs 行锁上序列化，先提交者胜出：
            //  - 撤销先提交：签发 422 被阻断，撤销 200；
            //  - 签发先提交：撤销凭旧版本号收到 409，刷新版本后重试撤销，仍完成并保留核销+记风险。
            if (issueOutcome == Outcome.ISSUED) {
                assertEquals(409, revokeStatus, "签发先提交时撤销须以版本冲突落败，不得重复释放/核销");
                int currentVersion = customsService.getLedger(customsNo).version();
                ServiceResult retryRevoke = customsService.revoke(new CustomsRevokeRequest(
                        "race-revoke-retry-" + round, customsNo, currentVersion, REVOKE_AT));
                assertEquals(200, retryRevoke.httpStatus(), "刷新版本后撤销仍应完成");

                CustomsLedgerView ledger = customsService.getLedger(customsNo);
                assertEquals("REVOKED", ledger.status());
                assertEquals(30, ledger.writtenOffQuantity(), "签发先成功则核销 30 支保持不变");
                assertEquals(0, ledger.reservedQuantity());
                assertEquals("WRITTEN_OFF", ledger.bindings().get(0).status());
                assertTrue(reviewService.listEvents(batchNo).stream()
                                .anyMatch(e -> e.get("eventType").equals("CUSTOMS_REVOKED_AFTER_ISSUE")),
                        "撤销晚于签发必须登记风险事件");
            } else {
                // 撤销先提交：签发被阻断，预占已释放
                assertEquals(Outcome.BLOCKED, issueOutcome);
                assertEquals(200, revokeStatus, "撤销先提交应成功");
                CustomsLedgerView ledger = customsService.getLedger(customsNo);
                assertEquals("REVOKED", ledger.status());
                assertEquals(0, ledger.writtenOffQuantity(), "撤销先成功则不得核销");
                assertEquals(0, ledger.reservedQuantity(), "撤销释放未签发预占");
                assertEquals("RELEASED", ledger.bindings().get(0).status());
                assertFalse(reviewService.listEvents(batchNo).stream()
                                .anyMatch(e -> e.get("eventType").equals("CUSTOMS_WRITTEN_OFF")),
                        "撤销先成功不得留下核销事件");
            }
        }
    }

    private enum Outcome {
        ISSUED, BLOCKED
    }
}
