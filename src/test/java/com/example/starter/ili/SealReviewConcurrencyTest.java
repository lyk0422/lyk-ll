package com.example.starter.ili;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ili.dto.BatchResultResponse;
import com.example.starter.ili.dto.DefectInput;
import com.example.starter.ili.dto.PairView;
import com.example.starter.ili.dto.ReviewRequest;
import com.example.starter.ili.dto.SealResponse;
import com.example.starter.ili.dto.SubmitBatchRequest;

/**
 * 封板与复核的真实并发竞态测试：两者对同批次必须形成一致结果，
 * 不允许出现封板成功响应后仍写入复核结论的情况。
 */
@SpringBootTest
class SealReviewConcurrencyTest {

    @Autowired
    private IliBatchService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM ili_match_pair");
        jdbc.update("DELETE FROM ili_defect_reading");
        jdbc.update("DELETE FROM ili_batch");
    }

    private long createBatchWithGrowingPair(String requestId) {
        SubmitBatchRequest req = new SubmitBatchRequest(
                requestId, "SEG-01", "RUN-2020", "RUN-2024",
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1),
                List.of(new DefectInput("O-1", 100.0, 10.0)),
                List.of(new DefectInput("N-1", 100.0, 30.0)));
        BatchResultResponse batch = service.submit(req);
        assertEquals("GROWING", batch.pairs().get(0).grade());
        return batch.batchId();
    }

    private long pairIdOf(long batchId) {
        return jdbc.queryForObject("SELECT id FROM ili_match_pair WHERE batch_id = ?",
                Long.class, batchId);
    }

    @Test
    void concurrentSealAndReviewAreConsistent() throws Exception {
        int iterations = 10;
        for (int i = 0; i < iterations; i++) {
            long batchId = createBatchWithGrowingPair("REQ-RACE-" + i);
            long pairId = pairIdOf(batchId);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                Future<Boolean> reviewAccepted = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        service.review(batchId,
                                new ReviewRequest(pairId, ReviewDecision.CONFIRM, "reviewer-race", "并发复核"));
                        return true;
                    } catch (ApiException e) {
                        assertEquals(HttpStatus.CONFLICT, e.status());
                        return false;
                    }
                });
                Future<SealResponse> sealResult = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.seal(batchId);
                });

                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                boolean accepted = reviewAccepted.get(15, TimeUnit.SECONDS);
                SealResponse seal = sealResult.get(15, TimeUnit.SECONDS);

                // 封板一定成功且为终态。
                assertEquals("SEALED", seal.status());
                String status = jdbc.queryForObject(
                        "SELECT status FROM ili_batch WHERE id = ?", String.class, batchId);
                assertEquals("SEALED", status);

                // 一致性：复核被接受则结论已落库；被拒绝（封板在前）则不得写入任何结论。
                String reviewStatus = jdbc.queryForObject(
                        "SELECT review_status FROM ili_match_pair WHERE id = ?",
                        String.class, pairId);
                if (accepted) {
                    assertEquals("CONFIRMED", reviewStatus);
                } else {
                    assertEquals("PENDING", reviewStatus);
                }
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentSealsAreIdempotent() throws Exception {
        long batchId = createBatchWithGrowingPair("REQ-SEAL-RACE");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch start = new CountDownLatch(1);
            var futures = new java.util.ArrayList<Future<SealResponse>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.seal(batchId);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            SealResponse first = futures.get(0).get(15, TimeUnit.SECONDS);
            for (int i = 1; i < futures.size(); i++) {
                // 所有并发封板都返回相同的幂等成功结果。
                assertEquals(first, futures.get(i).get(15, TimeUnit.SECONDS));
            }
            assertEquals("SEALED", first.status());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
