package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进口通关核销 H2 真实并发测试：
 * 1) 并发绑定预占累计不超总数；
 * 2) 撤销与签发竞争按提交顺序唯一；
 * 3) 并发同 requestId 绑定只执行一次并重放同一冻结结果。
 */
class CustomsConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T_SETTLE = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T23:59:59Z");
    private static final String ENTERPRISE = "合成生物制品厂";
    private static final String PRODUCT = "重组冠状病毒疫苗";

    @Autowired
    private CustomsService customsService;

    @Test
    void concurrentBindsNeverExceedQuota() throws Exception {
        clock.setFixed(T0);
        customsService.register(new CustomsRegisterRequest(
                "cc-reg", "CIQ-CC-Q", ENTERPRISE, PRODUCT, VALID_UNTIL, 100));
        int threads = 8;
        for (int i = 0; i < threads; i++) {
            String batchNo = "B-CC-Q-" + i;
            assertEquals(200, reviewService.declare(declareRequest("cc-d-" + i, batchNo,
                    allDocuments(), boundaryPassingInspections())).httpStatus());
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    // 乐观版本重试：读到当前版本后绑定，版本冲突重试，配额拒绝即终止。
                    for (int attempt = 0; attempt < 20; attempt++) {
                        int version = customsService.getLedger("CIQ-CC-Q").version();
                        ServiceResult result = customsService.bind("CIQ-CC-Q",
                                new CustomsBindRequest("cc-bind-" + index + "-" + attempt,
                                        version, "B-CC-Q-" + index, 30));
                        if (result.httpStatus() == 200) {
                            return true;
                        }
                        if (result.httpStatus() == 422) {
                            return false;
                        }
                    }
                    throw new IllegalStateException("版本冲突重试次数耗尽");
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发绑定必须在超时前全部返回");
        pool.shutdown();

        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        assertEquals(3, succeeded, "总数 100、每批 30：恰 3 批成功，第 4 批超量拒绝");
        CustomsDeclarationView ledger = customsService.getLedger("CIQ-CC-Q");
        assertEquals(90, ledger.reservedDoses(), "预占累计不得超总数");
        assertEquals(10, ledger.availableDoses());
        assertEquals(4, ledger.version(), "登记 1 次 + 成功绑定 3 次");
    }

    @Test
    void revokeAndIssueRaceHasSingleWinnerByCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            String batchNo = "B-CC-R-" + round;
            String declarationNo = "CIQ-CC-R-" + round;
            clock.setFixed(T0);
            assertEquals(200, reviewService.declare(declareRequest("cc-rd-" + round, batchNo,
                    allDocuments(), boundaryPassingInspections())).httpStatus());
            assertEquals(200, reviewService.evaluate(batchNo).httpStatus());
            clock.setFixed(T_SETTLE);
            assertEquals(200, reviewService.settlePublicity(batchNo).httpStatus());
            customsService.register(new CustomsRegisterRequest(
                    "cc-rr-" + round, declarationNo, ENTERPRISE, PRODUCT, VALID_UNTIL, 100));
            assertEquals(200, customsService.bind(declarationNo,
                    new CustomsBindRequest("cc-rb-" + round, 1, batchNo, 40)).httpStatus());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            final int raceRound = round;
            Future<ServiceResult> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reviewService.issue(batchNo, new IssueRequest("cc-ri-" + raceRound));
            });
            Future<ServiceResult> revokeFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return customsService.revoke(declarationNo, new CustomsRevokeRequest("cc-rv-" + raceRound, 2));
            });
            ServiceResult issue = issueFuture.get(30, TimeUnit.SECONDS);
            ServiceResult revoke = revokeFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(200, revoke.httpStatus(), "第 " + round + " 轮：撤销必须成功");
            CustomsDeclarationView ledger = customsService.getLedger(declarationNo);
            assertEquals("REVOKED", ledger.status());
            assertEquals(0, ledger.reservedDoses(), "第 " + round + " 轮：终态不得残留预占");
            String reviewStatus = reviewService.getReview(batchNo).status();
            if (issue.httpStatus() == 200) {
                // 签发先提交：已核销证据保留，撤销记风险事件。
                assertEquals(ReviewStatus.ISSUED.name(), reviewStatus);
                assertEquals(40, ledger.consumedDoses(), "第 " + round + " 轮：已签发必须已核销");
                assertEquals("CONSUMED", ledger.bindings().get(0).status());
                List<Map<String, Object>> events = reviewService.listEvents(batchNo);
                assertTrue(events.stream().anyMatch(e ->
                        e.get("eventType").equals("CUSTOMS_RISK_ISSUED_UNDER_REVOKED")),
                        "第 " + round + " 轮：已签发后撤销必须记风险事件");
            } else {
                // 撤销先提交：签发被阻断，不得出现已发证而余量未扣。
                assertEquals(422, issue.httpStatus(), "第 " + round + " 轮：签发只能成功或被阻断");
                assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewStatus);
                assertEquals(0, ledger.consumedDoses(), "第 " + round + " 轮：未签发不得核销");
                assertEquals("RELEASED", ledger.bindings().get(0).status());
            }
        }
    }

    @Test
    void concurrentSameRequestIdBindExecutesOnceAndReplays() throws Exception {
        clock.setFixed(T0);
        customsService.register(new CustomsRegisterRequest(
                "cc-reg-i", "CIQ-CC-I", ENTERPRISE, PRODUCT, VALID_UNTIL, 100));
        assertEquals(200, reviewService.declare(declareRequest("cc-di", "B-CC-I",
                allDocuments(), boundaryPassingInspections())).httpStatus());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<ServiceResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return customsService.bind("CIQ-CC-I",
                            new CustomsBindRequest("cc-idem-bind", 1, "B-CC-I", 10));
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发同键绑定必须在超时前全部返回");
        pool.shutdown();

        List<String> bodies = new ArrayList<>();
        for (Future<ServiceResult> future : futures) {
            ServiceResult result = future.get(10, TimeUnit.SECONDS);
            assertEquals(200, result.httpStatus(), "同键同参并发必须全部重放首次成功结果");
            bodies.add(jsonSupport.write(result.body()));
        }
        for (int i = 1; i < bodies.size(); i++) {
            assertEquals(bodies.get(0), bodies.get(i), "重放结果必须与首次冻结结果一致");
        }
        CustomsDeclarationView ledger = customsService.getLedger("CIQ-CC-I");
        assertEquals(10, ledger.reservedDoses(), "同键并发只能执行一次预占");
        assertEquals(1, ledger.bindings().size(), "同键并发只能落一行绑定");
        assertEquals(2, ledger.version());
    }
}
