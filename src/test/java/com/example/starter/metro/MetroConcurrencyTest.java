package com.example.starter.metro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 并发边界测试：同键重放只入账一次；重复流水号并发只有一笔成功。
 */
class MetroConcurrencyTest extends MetroTestBase {

    private static final int THREADS = 8;

    /** 并发请求结果。 */
    private record Outcome(int status, String body) {
    }

    @Test
    void concurrentIdenticalExitRequestsSettleOnlyOnce() throws Exception {
        entry("r-c0", "tx-c0", "TKC1", "S1", "2026-09-25T08:00:00");
        Map<String, String> exitBody = Map.of(
                "requestId", "r-cx", "txNo", "tx-cx", "ticketId", "TKC1",
                "station", "S3", "occurredAt", "2026-09-25T08:30:00");

        List<Outcome> outcomes = runConcurrently(THREADS, () -> {
            MvcResult result = postRaw("/api/metro/exit", exitBody);
            return new Outcome(result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());
        });

        for (Outcome outcome : outcomes) {
            assertEquals(200, outcome.status(), "同键同参并发请求应全部返回首次结果");
            assertEquals(outcomes.get(0).body(), outcome.body(), "重放响应应与首次结果一致");
        }
        // 只入账一次：出闸流水仅一条、一条已结算行程、一组清分明细
        Long exitTxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_transaction WHERE tx_no = 'tx-cx'", Long.class);
        assertEquals(1, exitTxCount);
        assertEquals(1, countRows("trip"));
        Long detailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_fen),0) FROM clearing_detail", Long.class);
        assertEquals(300L, detailSum);
        Long detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clearing_detail WHERE detail_type = 'SETTLE'", Long.class);
        assertEquals(1, detailCount);
    }

    @Test
    void concurrentDuplicateTxNoAllowsExactlyOnePosting() throws Exception {
        List<Outcome> outcomes = runConcurrently(THREADS, () -> {
            MvcResult result = postRaw("/api/metro/entry", Map.of(
                    "requestId", "r-dup-" + Thread.currentThread().threadId(),
                    "txNo", "tx-dup-concurrent", "ticketId", "TKC2",
                    "station", "S1", "occurredAt", "2026-09-25T08:00:00"));
            return new Outcome(result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());
        });

        long ok = outcomes.stream().filter(o -> o.status() == 200).count();
        long conflict = outcomes.stream().filter(o -> o.status() == 409).count();
        assertEquals(1, ok, "重复流水号并发只能成功一笔");
        assertEquals(THREADS - 1, conflict, "其余并发请求应被 409 拒绝");
        assertEquals(1, countRows("metro_transaction"));
        assertEquals(1, countRows("trip"));
    }

    /**
     * 真实并发执行：所有线程在同一栅栏后同时发起请求，30 秒超时。
     */
    private List<Outcome> runConcurrently(int threads, Callable<Outcome> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(30, TimeUnit.SECONDS));
                    return task.call();
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
