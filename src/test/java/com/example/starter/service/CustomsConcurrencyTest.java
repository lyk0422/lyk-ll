package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.BindSubBatchRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.RegisterDeclarationRequest;
import com.example.starter.dto.RevokeDeclarationRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 * 通关台账 H2 真实并发测试：
 * 1) 并发绑定竞争预占，累计绝不超通关单总数；
 * 2) 撤销与签发竞争按提交顺序唯一，已签发证据与核销账目始终一致；
 * 3) 并发同 requestId 同参绑定仅执行一次。
 */
class CustomsConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant DECLARE_TIME = Instant.parse("2026-09-07T02:00:00Z");
    private static final Instant SETTLE_TIME = Instant.parse("2026-09-14T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");

    private void register(String declarationNo, int total) {
        customsService.register(new RegisterDeclarationRequest("reg-" + declarationNo, declarationNo,
                "合成生物制品厂", "重组冠状病毒疫苗", VALID_UNTIL, total));
    }

    private void driveToEffective(String batchNo) {
        clock.setFixed(DECLARE_TIME);
        reviewService.declare(declareRequest("d-" + batchNo, batchNo, allDocuments(),
                boundaryPassingInspections()));
        reviewService.evaluate(batchNo);
        clock.setFixed(SETTLE_TIME);
        reviewService.settlePublicity(batchNo);
    }

    /** 带版本重试的绑定：409 版本冲突时重读版本再试，直到成功或业务拒绝。 */
    private ServiceResult bindWithRetry(String declarationNo, String requestId, String batchNo, int quantity) {
        for (int i = 0; i < 50; i++) {
            int version = customsService.getDeclaration(declarationNo).version();
            ServiceResult result = customsService.bind(declarationNo,
                    new BindSubBatchRequest(requestId, batchNo, quantity, version));
            if (result.httpStatus() != 409) {
                return result;
            }
        }
        throw new IllegalStateException("绑定重试超过上限");
    }

    @Test
    void concurrentBindingsNeverExceedTotalQuantity() throws Exception {
        clock.setFixed(DECLARE_TIME);
        register("CUS-CONC-1", 100);
        int threads = 8;
        for (int i = 0; i < threads; i++) {
            driveToEffective("B-CONC-1-" + i);
        }
        clock.setFixed(SETTLE_TIME);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<ServiceResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    // 每个线程绑定自己的子批 30 支；总数 100 只允许 3 笔成功
                    return bindWithRetry("CUS-CONC-1", "conc-bind-" + index, "B-CONC-1-" + index, 30);
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发绑定必须在超时前全部返回");
        pool.shutdown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<ServiceResult> future : futures) {
            int status = future.get(10, TimeUnit.SECONDS).httpStatus();
            if (status == 200) {
                succeeded++;
            } else if (status == 422) {
                rejected++;
            }
        }
        assertEquals(3, succeeded, "总数 100、每笔 30，必须恰好 3 笔绑定成功");
        assertEquals(threads - 3, rejected, "其余绑定必须因超额 422 拒绝");

        CustomsDeclarationView view = customsService.getDeclaration("CUS-CONC-1");
        assertEquals(90, view.reservedQuantity(), "累计预占绝不超总数");
        assertEquals(10, view.remainingQuantity());
        assertEquals(3, view.bindings().size(), "只能有 3 条绑定记录");
        assertTrue(view.reservedQuantity() + view.consumedQuantity() <= view.totalQuantity());
    }

    @Test
    void revokeAndIssueRaceSettlesInSubmissionOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            String declarationNo = "CUS-RACE-" + round;
            String batchNo = "B-RACE-ISSUE-" + round;
            clock.setFixed(DECLARE_TIME);
            register(declarationNo, 100);
            driveToEffective(batchNo);
            clock.setFixed(SETTLE_TIME);
            assertEquals(200, customsService.bind(declarationNo,
                    new BindSubBatchRequest("race-bind-" + round, batchNo, 40, 1)).httpStatus());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            final String issueRequestId = "race-issue-" + round;
            final String revokeRequestId = "race-revoke-" + round;
            Future<Integer> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reviewService.issue(batchNo, new IssueRequest(issueRequestId)).httpStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                // 版本冲突时重读重试，直到撤销提交
                for (int i = 0; i < 50; i++) {
                    int version = customsService.getDeclaration(declarationNo).version();
                    ServiceResult result = customsService.revoke(declarationNo,
                            new RevokeDeclarationRequest(revokeRequestId, version));
                    if (result.httpStatus() != 409) {
                        return result.httpStatus();
                    }
                }
                return -1;
            });

            int issueStatus = issueFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(200, revokeStatus, "第 " + round + " 轮：撤销最终必须提交成功");
            assertTrue(issueStatus == 200 || issueStatus == 422,
                    "第 " + round + " 轮：签发只能成功或被撤销阻断，实际 " + issueStatus);

            String reviewStatus = reviewService.getReview(batchNo).status();
            CustomsDeclarationView view = customsService.getDeclaration(declarationNo);
            assertTrue(view.revoked());
            assertEquals(0, view.reservedQuantity(), "第 " + round + " 轮：终局不得残留预占");
            if (issueStatus == 200) {
                // 签发先提交：已发证则必须已核销，撤销不得动已核销证据
                assertEquals(ReviewStatus.ISSUED.name(), reviewStatus);
                assertEquals(40, view.consumedQuantity(), "第 " + round + " 轮：已发证必须已扣余量");
                assertEquals("CONSUMED", view.bindings().get(0).status());
                long riskEvents = customsService.listEvents(declarationNo).stream()
                        .filter(e -> e.get("eventType").equals("RISK_ISSUED_UNDER_REVOKED")).count();
                assertEquals(1, riskEvents, "第 " + round + " 轮：已签发子批必须记风险事件");
            } else {
                // 撤销先提交：签发被阻断，预占已释放
                assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewStatus);
                assertEquals(0, view.consumedQuantity(), "第 " + round + " 轮：未签发不得核销");
                assertEquals("RELEASED", view.bindings().get(0).status());
            }
            assertEquals(view.totalQuantity(),
                    view.reservedQuantity() + view.consumedQuantity() + view.remainingQuantity(),
                    "第 " + round + " 轮：台账恒等式必须成立");
        }
    }

    @Test
    void concurrentSameRequestIdBindsExactlyOnce() throws Exception {
        clock.setFixed(DECLARE_TIME);
        register("CUS-CONC-2", 100);
        driveToEffective("B-CONC-2");
        clock.setFixed(SETTLE_TIME);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<ServiceResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    // 同 requestId 同参数并发：仅首次执行，其余重放
                    return customsService.bind("CUS-CONC-2",
                            new BindSubBatchRequest("conc-idem-bind", "B-CONC-2", 30, 1));
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发同键绑定必须在超时前全部返回");
        pool.shutdown();

        for (Future<ServiceResult> future : futures) {
            assertEquals(200, future.get(10, TimeUnit.SECONDS).httpStatus(),
                    "同键同参并发必须全部重放首次成功结果");
        }
        CustomsDeclarationView view = customsService.getDeclaration("CUS-CONC-2");
        assertEquals(30, view.reservedQuantity(), "同键并发只能预占一次");
        assertEquals(1, view.bindings().size(), "同键并发只能产生一条绑定");
        assertEquals(2, view.version(), "同键并发只能成功变更一次版本");
    }
}
