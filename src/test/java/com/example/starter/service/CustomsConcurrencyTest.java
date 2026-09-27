package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通关核销 H2 真实并发测试：
 * 1) 撤销与签发竞争按提交顺序唯一——不得既发证又释放预占；
 * 2) 多子批并发绑定同一通关单，累计预占不得超过可核销总数。
 */
class CustomsConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-07T02:00:00Z");
    private static final LocalDate VALID_UNTIL = LocalDate.of(2026, 10, 31);
    private static final Instant OCCURRED = Instant.parse("2026-09-07T02:00:00Z");

    @Autowired
    private CustomsService customsService;

    @BeforeEach
    void fixClock() {
        clock.setFixed(T0);
    }

    @Test
    void revokeAndIssueRaceProducesSingleConsistentOutcome() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int raceRound = round;
            String declarationNo = "CD-RACE-" + raceRound;
            String batchNo = "B-RACE-ISSUE-" + raceRound;
            register(declarationNo, 100);
            declareBatch("race-d-" + raceRound, batchNo);
            assertEquals(200, customsService.bind(new CustomsBindRequest(
                    "race-b-" + raceRound, declarationNo, batchNo, 10, 1, OCCURRED)).httpStatus());
            toPublicityEffective(batchNo);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<ServiceResult> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reviewService.issue(batchNo, new IssueRequest("race-i-" + raceRound));
            });
            Future<ServiceResult> revokeFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return customsService.revoke(
                        new CustomsRevokeRequest("race-v-" + raceRound, declarationNo, 2, OCCURRED));
            });
            ServiceResult issue = issueFuture.get(30, TimeUnit.SECONDS);
            ServiceResult revoke = revokeFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            boolean issuedWon = issue.httpStatus() == 200;
            boolean issueBlocked = issue.httpStatus() == 422;
            assertTrue(issuedWon || issueBlocked,
                    "第 " + round + " 轮：签发只能成功或被 422 阻断，实际 " + issue.httpStatus());
            assertEquals(200, revoke.httpStatus(), "第 " + round + " 轮：撤销必须成功");

            String reservationStatus = jdbc.queryForObject("""
                    SELECT r.status FROM customs_reservation r
                    JOIN batch_review b ON b.id = r.review_id WHERE b.batch_no = ?
                    """, String.class, batchNo);
            String reviewStatus = reviewService.getReview(batchNo).status();
            if (issuedWon) {
                // 签发先提交：已发证且预占转已核销，撤销不得释放已核销数量。
                assertEquals(ReviewStatus.ISSUED.name(), reviewStatus);
                assertEquals("WRITTEN_OFF", reservationStatus,
                        "第 " + round + " 轮：不能已发证而余量未扣");
                assertTrue(customsService.listRiskEvents(declarationNo).stream()
                        .anyMatch(e -> e.get("eventType").equals("ISSUED_EVIDENCE_RETAINED_AFTER_REVOKE")));
            } else {
                // 撤销先提交：预占释放，签发被阻断，不得出现既释放又发证。
                assertEquals("RELEASED", reservationStatus);
                assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewStatus);
            }
            CustomsDeclarationView view = customsService.getDeclaration(declarationNo);
            assertTrue(view.revoked());
            assertEquals(issuedWon ? 10 : 0, view.writtenOffQuantity());
            assertEquals(0, view.reservedQuantity(), "第 " + round + " 轮：终态不得残留预占");
        }
    }

    @Test
    void concurrentBindsNeverExceedDeclarationQuota() throws Exception {
        register("CD-RACE-Q", 100);
        int threads = 6;
        for (int i = 0; i < threads; i++) {
            declareBatch("race-q-d-" + i, "B-RACE-Q-" + i);
        }
        // 每个线程 30 支，6 × 30 = 180 > 100：最多 3 个绑定成功（恰占满 90），其余 422。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    // 版本竞争落败时以最新版本重试，验证最终一致性而非版本冲突本身。
                    for (int attempt = 0; attempt < 20; attempt++) {
                        int version = customsService.getDeclaration("CD-RACE-Q").version();
                        ServiceResult result = customsService.bind(new CustomsBindRequest(
                                "race-q-b-" + index + "-" + attempt, "CD-RACE-Q",
                                "B-RACE-Q-" + index, 30, version, OCCURRED));
                        if (result.httpStatus() == 422 && attempt < 19) {
                            String body = String.valueOf(result.body());
                            if (body.contains("VERSION_MISMATCH")) {
                                continue;
                            }
                        }
                        return result.httpStatus();
                    }
                    return -1;
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发绑定必须在超时前全部返回");
        pool.shutdown();

        int succeeded = 0;
        int quotaRejected = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(10, TimeUnit.SECONDS);
            if (status == 200) {
                succeeded++;
            } else if (status == 422) {
                quotaRejected++;
            }
        }
        assertEquals(3, succeeded, "100 支通关单最多容纳 3 个 30 支子批");
        assertEquals(threads - 3, quotaRejected, "其余并发绑定必须 422 超量拒绝");

        CustomsDeclarationView view = customsService.getDeclaration("CD-RACE-Q");
        assertEquals(90, view.reservedQuantity(), "累计预占不得超过可核销总数");
        assertEquals(10, view.availableQuantity());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customs_reservation WHERE status = 'RESERVED'", Integer.class);
        assertEquals(3, rows == null ? 0 : rows);
    }

    private void register(String declarationNo, int totalQuantity) {
        assertEquals(200, customsService.register(new CustomsRegisterRequest(
                "race-r-" + declarationNo, declarationNo, "合成生物制品厂", "重组冠状病毒疫苗",
                VALID_UNTIL, totalQuantity)).httpStatus());
    }

    private void declareBatch(String requestId, String batchNo) {
        assertEquals(200, reviewService.declare(declareRequest(requestId, batchNo,
                allDocuments(), boundaryPassingInspections())).httpStatus());
    }

    private void toPublicityEffective(String batchNo) {
        assertEquals(200, reviewService.evaluate(batchNo).httpStatus());
        var view = reviewService.getReview(batchNo);
        clock.setFixed(view.publicityEndUtc().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertEquals(200, reviewService.settlePublicity(batchNo).httpStatus());
        clock.setFixed(T0);
    }
}
