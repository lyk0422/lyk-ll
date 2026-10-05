package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 影像差异工单 H2 事务/并发测试：
 * 并发归还比对仅一个进行中工单（唯一水位约束兜底）；结案与工单闭环并发时结案被原子拦截。
 */
class ImageConcurrencyTest extends MuseumTestSupport {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 2, 1);

    private long contractWithOneDiff() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        imageHandoverService.registerFeatures(contractId, "RETURN", List.of(
                feature("FEATURE_SEAL", "seal-hash-CHANGED"),
                feature("FEATURE_EDGE", "edge-intact-B2"),
                feature("FEATURE_PATCH", "patch-37-C3")));
        // 先把合同推进到归还完成（比对会生成一个工单，随后清理以构造干净的并发起点）
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
        jdbc.update("DELETE FROM discrepancy_item");
        jdbc.update("DELETE FROM discrepancy_order");
        return contractId;
    }

    @Test
    void concurrentReturnComparisonsCreateExactlyOneOpenOrder() throws Exception {
        long contractId = contractWithOneDiff();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                Map<String, Object> view = imageHandoverService.compareOnReturn(contractId, null);
                return ((Number) view.get("discrepancyOrderId")).longValue();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        long firstOrderId = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<Long> future : futures) {
            // 所有并发调用要么首次生成、要么复用，最终指向同一个进行中工单
            assertEquals(firstOrderId, future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, count("discrepancy_order"), "同一合同同时刻仅允许一个进行中差异工单");
        // 差异明细仅落库一次，未被复用路径重复写入
        assertEquals(1, count("discrepancy_item"));
        Integer open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_order WHERE contract_id = ? AND status = 'OPEN'",
                Integer.class, contractId);
        assertEquals(1, open);
    }

    @Test
    void closeAndOrderClosureRaceKeepsCloseBlockedUntilClosed() throws Exception {
        long contractId = contractWithOneDiff();
        // 先生成一个进行中工单
        Map<String, Object> comparison = imageHandoverService.compareOnReturn(contractId, null);
        long orderId = ((Number) comparison.get("discrepancyOrderId")).longValue();

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
        Future<String> closureFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            imageHandoverService.closeOrder(orderId, "点交员王五");
            return "ORDER_CLOSED";
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        String closeOutcome = closeFuture.get(30, TimeUnit.SECONDS);
        assertEquals("ORDER_CLOSED", closureFuture.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 结案与工单状态原子判定：与闭环并发的结案若先到必须被拦截
        if ("BLOCKED".equals(closeOutcome)) {
            assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));
        }
        // 闭环完成后再结案必然成功，最终状态收敛为 CLOSED
        assertEquals(LoanService.STATUS_CLOSED, loanService.close(contractId).get("status"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_order WHERE contract_id = ? AND status = 'OPEN'",
                Integer.class, contractId));
    }
}
