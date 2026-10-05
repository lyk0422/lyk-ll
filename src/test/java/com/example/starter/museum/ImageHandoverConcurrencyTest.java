package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 影像点交并发与 H2 事务测试：
 * 同一合同并发归还影像登记恰好生成一个进行中差异工单；
 * 结案与工单闭环并发时结案校验与工单状态原子判定；
 * 进行中差异工单唯一约束由 H2 实际约束保证。
 */
class ImageHandoverConcurrencyTest extends MuseumTestSupport {

    private static final List<ImageHandoverService.ImageFeature> BASELINE =
            features("CORNER", "hash-a1", "GLAZE", "hash-b2", "INSCRIPTION", "hash-c3");
    private static final List<ImageHandoverService.ImageFeature> WITH_DIFF =
            features("CORNER", "hash-a1", "GLAZE", "CHANGED", "INSCRIPTION", "hash-c3");

    /** 申请→出借影像基线→双方交付确认，进入在展。 */
    private long onDisplayWithBaseline() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)).get("contractId"))
                .longValue();
        imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_DELIVERY, BASELINE);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_BORROWER);
        return contractId;
    }

    @Test
    void concurrentReturnRegistrationCreatesExactlyOneOpenOrder() throws Exception {
        long contractId = onDisplayWithBaseline();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    imageHandoverService.registerFeatures(newRequestId(), contractId,
                            ImageHandoverService.HANDOVER_RETURN, WITH_DIFF);
                    return "OK";
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    return "CONFLICT";
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<String> future : futures) {
            if ("OK".equals(future.get(30, TimeUnit.SECONDS))) {
                ok++;
            } else {
                conflict++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "并发归还登记必须串行且仅一份清单生效");
        assertEquals(threads - 1, conflict);
        // 同一合同同时刻仅一个进行中差异工单；归还特征只落一份
        assertEquals(1, count("difference_order"));
        assertEquals(1, count("contract_open_diff_order"));
        long returnHandoverId = jdbc.queryForObject(
                "SELECT id FROM handover WHERE contract_id = ? AND handover_type = 'RETURN'",
                Long.class, contractId);
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handover_image_feature WHERE handover_id = ?",
                Long.class, returnHandoverId));
        assertTrue(imageHandoverService.hasOpenOrder(contractId));
    }

    @Test
    void concurrentCloseAndResolveYieldsAtomicOutcome() throws Exception {
        long contractId = onDisplayWithBaseline();
        // 归还影像登记产生差异工单，随后双方完成归还点交确认（合同进入 RETURNED，工单仍 OPEN）
        var reg = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN, WITH_DIFF);
        long orderId = ((Number) reg.get("orderId")).longValue();
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
        assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> closeFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                loanService.close(contractId);
                return "CLOSED";
            } catch (ApiException e) {
                assertEquals(HttpStatus.CONFLICT, e.status());
                return "BLOCKED";
            }
        });
        Future<String> resolveFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            imageHandoverService.resolveOrder(orderId, "点交员张三");
            return "RESOLVED";
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        String closeOutcome = closeFuture.get(30, TimeUnit.SECONDS);
        String resolveOutcome = resolveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals("RESOLVED", resolveOutcome);

        // 原子判定：工单闭环与结案经合同行锁串行。
        // 若结案先执行必被未闭环工单拦截（合同仍 RETURNED）；闭环后再次结案必须成功。
        if ("CLOSED".equals(closeOutcome)) {
            assertEquals(LoanService.STATUS_CLOSED, contractStatus(contractId));
            assertEquals(0, count("contract_open_diff_order"));
        } else {
            assertEquals("BLOCKED", closeOutcome);
            assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));
            // 闭环已完成后，结案放行
            assertEquals(LoanService.STATUS_CLOSED, loanService.close(contractId).get("status"));
        }
    }

    @Test
    void openOrderUniqueConstraintEnforcedByH2() {
        long contractId = onDisplayWithBaseline();
        var reg = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN, WITH_DIFF);
        long firstOrderId = ((Number) reg.get("orderId")).longValue();
        // H2 主键即唯一约束：同合同第二个进行中工单行必须被数据库拒绝
        assertDuplicateKey(() -> jdbc.update(
                "INSERT INTO contract_open_diff_order (contract_id, order_id) VALUES (?,?)",
                contractId, firstOrderId + 999));
        assertEquals(1, count("contract_open_diff_order"));
        // 闭环删除唯一行后，允许下一进行中工单占位
        imageHandoverService.resolveOrder(firstOrderId, "点交员张三");
        jdbc.update("INSERT INTO contract_open_diff_order (contract_id, order_id) VALUES (?,?)",
                contractId, firstOrderId);
        assertEquals(1, count("contract_open_diff_order"));
    }

    private void assertDuplicateKey(RunnableThrowing action) {
        try {
            action.run();
        } catch (DuplicateKeyException e) {
            return;
        }
        throw new AssertionError("期望 H2 抛出唯一约束冲突 DuplicateKeyException");
    }

    private interface RunnableThrowing {
        void run();
    }
}
