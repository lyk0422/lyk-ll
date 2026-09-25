package com.example.starter.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.starter.transfer.TransferDtos.OrderResponse;
import com.example.starter.transfer.TransferDtos.VerifyItemRequest;
import com.example.starter.transfer.TransferDtos.VerifyRequest;

/**
 * 并发与竞态测试：撤销/核注唯一终态、并发核销不超余量、并发同键申请幂等。
 * 所有并发任务通过 CountDownLatch 同时起跑并设置超时，断言最终结果与落库数据。
 */
class TransferConcurrencyTest extends BaseTransferTest {

    /** 撤销与在途核注并发：形成唯一终态，不得既撤销又核注。 */
    @Test
    void cancelAndEndorseRaceYieldsSingleTerminalState() throws Exception {
        for (int round = 0; round < 10; round++) {
            String suffix = "-" + round;
            seedStock("WH-A", TARIFF_A, 100);
            OrderResponse order = service.apply(applyRequest("REQ-C" + suffix, "ENT-C", 40));
            long orderId = order.orderId();

            AtomicInteger endorseOk = new AtomicInteger();
            AtomicInteger cancelOk = new AtomicInteger();
            runConcurrently(List.of(
                    () -> {
                        guard(() -> service.endorse(orderId), endorseOk);
                        return null;
                    },
                    () -> {
                        guard(() -> service.cancel(orderId), cancelOk);
                        return null;
                    }));

            assertEquals(1, endorseOk.get() + cancelOk.get(),
                    "核注与撤销必须恰好一个成功，round=" + round);
            String status = statusOf(orderId);
            if (cancelOk.get() == 1) {
                assertEquals("CANCELLED", status);
                assertEquals(100, stockOf("WH-A", TARIFF_A), "撤销成功则库存不得扣减");
            } else {
                assertEquals("IN_TRANSIT", status);
                assertEquals(60, stockOf("WH-A", TARIFF_A), "核注成功则库存必须扣减");
            }
            jdbc.update("DELETE FROM transfer_status_history");
            jdbc.update("DELETE FROM transfer_order_item");
            jdbc.update("DELETE FROM transfer_order");
            jdbc.update("DELETE FROM warehouse_stock");
        }
    }

    /** 同一商品项并发核销：不得超过可用余量，超量者整批拒绝。 */
    @Test
    void concurrentVerifyNeverExceedsRemaining() throws Exception {
        seedStock("WH-A", TARIFF_A, 100);
        OrderResponse order = service.apply(applyRequest("REQ-V", "ENT-V", 10));
        service.endorse(order.orderId());
        long orderId = order.orderId();

        // 5 个并发核销各请求 4，余量 10 至多成功 2 个
        int threads = 5;
        AtomicInteger success = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "V-C-" + i;
            tasks.add(() -> {
                guard(() -> service.verify(orderId,
                        new VerifyRequest(requestId, List.of(new VerifyItemRequest(TARIFF_A, 4)))),
                        success);
                return null;
            });
        }
        runConcurrently(tasks);

        long verified = verifiedOf(orderId, TARIFF_A);
        assertTrue(success.get() <= 2, "成功核销批次数不得超过余量允许: " + success.get());
        assertEquals(success.get() * 4L, verified, "落库核销数量必须等于成功批次之和");
        assertTrue(verified <= 10, "累计核销不得超过核注数量");
    }

    /** 并发同键同参申请：全部返回首次结果，仅落库一单。 */
    @Test
    void concurrentApplyWithSameKeyIsIdempotent() throws Exception {
        int threads = 4;
        List<OrderResponse> results = new ArrayList<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                synchronized (results) {
                    results.add(service.apply(applyRequest("REQ-A", "ENT-A", 10)));
                }
                return null;
            });
        }
        runConcurrently(tasks);

        assertEquals(threads, results.size());
        long firstId = results.get(0).orderId();
        assertTrue(results.stream().allMatch(r -> r.orderId() == firstId),
                "同键并发申请必须返回同一单据");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE request_id = 'REQ-A'", Integer.class);
        assertEquals(1, count);
    }

    private static void guard(Runnable action, AtomicInteger successCounter) {
        try {
            action.run();
            successCounter.incrementAndGet();
        } catch (ApiException expected) {
            // 竞态失败方：业务冲突拒绝，属预期分支
        }
    }

    private static void runConcurrently(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "并发起跑超时");
                    task.call();
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发就绪超时");
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池回收超时");
        }
    }
}
