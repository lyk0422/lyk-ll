package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.DeclareRequest;
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

/**
 * 同一 requestId 并发重放：只允许一个请求执行业务，其余重放首次结果，
 * 不产生重复流程、不出现脏占位响应。
 */
class ConcurrentIdempotencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentSameRequestIdProducesSingleBusinessExecution() throws Exception {
        clock.setFixed(Instant.parse("2026-09-07T02:00:00Z"));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<ServiceResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                DeclareRequest request = declareRequest("same-idem-key", "B-CONC-IDEM",
                        allDocuments(), boundaryPassingInspections());
                barrier.await(5, TimeUnit.SECONDS);
                return reviewService.declare(request);
            }));
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<ServiceResult> future : futures) {
            ServiceResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(200, result.httpStatus(), "并发同键必须全部重放首次 200 结果");
            statuses.add(result.httpStatus());
        }
        pool.shutdown();

        assertEquals(threads, statuses.size());
        assertEquals(1, countReviews("B-CONC-IDEM"), "并发同键重放只允许产生一个审查流程");
        assertEquals(1, countIdempotencyRecords("same-idem-key"), "幂等记录只能有一行");
    }

    private int countReviews(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_review WHERE batch_no = ?", Integer.class, batchNo);
        return count == null ? 0 : count;
    }

    private int countIdempotencyRecords(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }
}
