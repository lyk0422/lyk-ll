package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.ColdChainLedgerView;
import com.example.starter.dto.ColdChainSubmitRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsLedgerView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链 H2 真实并发测试：
 * 1) 更正与签发竞争：行锁序列化后，要么补录先提交、签发重核新版本温证，
 *    要么签发先成、补录仅登记风险且不回改旧证书与核销数量；
 * 2) 并发补录同版本：恰一笔成功前进版本，其余 409，绝不产生分叉版本。
 */
class ColdChainConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");
    private static final Instant V1_START = Instant.parse("2026-08-31T10:00:00Z");
    private static final Instant V1_SUBMITTED_AT = Instant.parse("2026-08-31T10:30:00Z");
    private static final Instant V2_START = Instant.parse("2026-08-31T11:00:00Z");
    private static final Instant V2_SUBMITTED_AT = Instant.parse("2026-08-31T11:30:00Z");

    private void prepareBoundBatchWithColdChain(String suffix) {
        clock.setFixed(T0);
        customsService.register(new CustomsRegisterRequest("cr-reg-" + suffix, "CI-CR-" + suffix,
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, VALID_UNTIL, 100, T0));
        prepareIssueableImportBatch("cr-d-" + suffix, "B-CR-" + suffix);
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity("B-CR-" + suffix);
        customsService.bind(new CustomsBindRequest("cr-bind-" + suffix, "CI-CR-" + suffix,
                "B-CR-" + suffix, 30, 1, T0));
        ServiceResult submitted = coldChainService.submit("B-CR-" + suffix,
                new ColdChainSubmitRequest("cr-sub-" + suffix, 0, V1_SUBMITTED_AT,
                        qualifiedTwoSegments(V1_START)));
        assertEquals(200, submitted.httpStatus());
    }

    @Test
    void supplementAndIssueRaceHasExactlyOneConsistentOutcome() throws Exception {
        for (int round = 0; round < 10; round++) {
            final String batchNo = "B-CR-" + round;
            prepareBoundBatchWithColdChain(String.valueOf(round));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            final int r = round;

            Future<ServiceResult> supplementFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                // 更正：新版本合格证据（运输区间平移 1 小时），期望版本 1。
                return coldChainService.submit(batchNo,
                        new ColdChainSubmitRequest("cr-sup-" + r, 1, V2_SUBMITTED_AT,
                                qualifiedTwoSegments(V2_START)));
            });
            Future<ServiceResult> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reviewService.issue(batchNo, new IssueRequest("cr-issue-" + r));
            });

            ServiceResult supplementResult = supplementFuture.get(30, TimeUnit.SECONDS);
            ServiceResult issueResult = issueFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            ColdChainLedgerView ledger = coldChainService.getLedger(batchNo);
            List<java.util.Map<String, Object>> events = reviewService.listEvents(batchNo);
            boolean riskOnly = events.stream()
                    .anyMatch(e -> e.get("eventType").equals("COLDCHAIN_SUPPLEMENT_AFTER_ISSUE"));
            String certPayload = events.stream()
                    .filter(e -> e.get("eventType").equals("COLDCHAIN_CERT_VERIFIED"))
                    .map(e -> String.valueOf(e.get("payload")))
                    .findFirst().orElse("");

            assertEquals(200, supplementResult.httpStatus(), "两种次序下补录都返回 200（新版或仅风险）");
            assertEquals(200, issueResult.httpStatus(), "两种次序下签发都应成功（新旧温证均合格）");
            if (riskOnly) {
                // 签发先成：补录仅加风险，版本停留 1，旧温证与核销数量不变。
                assertEquals(1, ledger.version(), "签发先成则补录不得推进版本");
                assertEquals(1, ledger.certVersion());
                assertTrue(certPayload.contains("\"version\":1"), "签发须冻结版本 1 温证: " + certPayload);
            } else {
                // 补录先成：签发重核新版本温证。
                assertEquals(2, ledger.version(), "补录先成则版本前进到 2");
                assertEquals(2, ledger.certVersion());
                assertTrue(certPayload.contains("\"version\":2"), "签发须重核版本 2 温证: " + certPayload);
            }
            CustomsLedgerView customs = customsService.getLedger("CI-CR-" + round);
            assertEquals(30, customs.writtenOffQuantity(), "任一次序都恰核销 30 支，不重复核销");
            assertEquals(0, customs.reservedQuantity());
        }
    }

    @Test
    void concurrentSupplementsOnSameVersionHaveExactlyOneWinner() throws Exception {
        prepareBoundBatchWithColdChain("dup");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return coldChainService.submit("B-CR-dup",
                            new ColdChainSubmitRequest("cr-dup-" + index, 1, V2_SUBMITTED_AT,
                                    qualifiedTwoSegments(V2_START))).httpStatus();
                } catch (BusinessException ex) {
                    return ex.getHttpStatus();
                }
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(),
                "同一期望版本并发补录恰一笔成功");
        assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count(),
                "落败方必须 409 版本冲突");
        ColdChainLedgerView ledger = coldChainService.getLedger("B-CR-dup");
        assertEquals(2, ledger.version(), "版本只前进一次，不产生分叉");
        assertEquals(2, ledger.certVersion());
        assertTrue(ledger.certValid());
    }
}
