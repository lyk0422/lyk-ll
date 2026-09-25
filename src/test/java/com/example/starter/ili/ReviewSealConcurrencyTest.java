package com.example.starter.ili;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复核与封板竞态的 H2 并发测试：并发操作在批次行锁上串行化，
 * 不允许出现封板后仍写入复核结论的不一致状态。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ili_test_conc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewSealConcurrencyTest {

    @Autowired
    private AlignmentService alignmentService;

    @Autowired
    private SegmentService segmentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM match_pair");
        jdbc.update("DELETE FROM defect_reading");
        jdbc.update("DELETE FROM alignment_batch");
        jdbc.update("DELETE FROM inspection_run");
        jdbc.update("DELETE FROM pipeline_weld");
        jdbc.update("DELETE FROM pipeline_segment");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    private long importGrowingBatch(String requestId, int pairCount) {
        segmentService.createSegment(TestData.segmentRequest());
        return alignmentService.importAndAlign(TestData.growingPairsImport(requestId, pairCount))
                .response().batchId();
    }

    private List<Long> pairIds(long batchId) {
        return jdbc.queryForList("SELECT id FROM match_pair WHERE batch_id = ? ORDER BY id",
                Long.class, batchId);
    }

    @Test
    void concurrentReviewsAndSealProduceConsistentFinalState() throws Exception {
        int pairCount = 6;
        long batchId = importGrowingBatch("REQ-CONC-SEAL", pairCount);
        List<Long> pairs = pairIds(batchId);

        ExecutorService pool = Executors.newFixedThreadPool(pairCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reviewSuccess = new AtomicInteger();
        AtomicInteger reviewRejected = new AtomicInteger();
        CopyOnWriteArrayList<String> sealOutcome = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new java.util.ArrayList<>();

        // 每个匹配对一个复核线程
        for (Long pairId : pairs) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    alignmentService.review(batchId,
                            new Dtos.ReviewRequest(pairId, Dtos.ReviewDecision.CONFIRM, "r-" + pairId));
                    reviewSuccess.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    reviewRejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        // 一个并发封板线程
        futures.add(pool.submit(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                alignmentService.seal(batchId);
                sealOutcome.add("SEALED");
            } catch (ApiException e) {
                assertEquals(HttpStatus.CONFLICT, e.status());
                sealOutcome.add("REJECTED");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(pairCount, reviewSuccess.get() + reviewRejected.get());
        assertEquals(1, sealOutcome.size());

        String status = jdbc.queryForObject("SELECT status FROM alignment_batch WHERE id = ?",
                String.class, batchId);
        int pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_pair WHERE batch_id = ? AND review_status = 'PENDING'",
                Integer.class, batchId);
        int confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_pair WHERE batch_id = ? AND review_status = 'CONFIRMED'",
                Integer.class, batchId);

        // 一致性不变量：落库的复核结论数 == 成功的复核请求数
        assertEquals(reviewSuccess.get(), confirmed);
        if ("SEALED".equals(status)) {
            // 封板成功 => 封板时所有复核已完成，且封板后的复核全部被拒绝、未写入
            assertEquals(0, pending);
            assertNotNull(jdbc.queryForObject("SELECT sealed_at FROM alignment_batch WHERE id = ?",
                    java.sql.Timestamp.class, batchId));
        } else {
            // 封板被拒绝 => 说明当时仍有待复核项；所有复核最终成功，可再次封板
            assertEquals("ALIGNED", status);
            assertEquals("REJECTED", sealOutcome.get(0));
            assertEquals(pairCount, reviewSuccess.get());
            alignmentService.seal(batchId);
            assertEquals("SEALED", jdbc.queryForObject(
                    "SELECT status FROM alignment_batch WHERE id = ?", String.class, batchId));
        }
        // 终态后任何复核都必须被拒绝且不改数据
        int finalConfirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_pair WHERE batch_id = ? AND review_status = 'CONFIRMED'",
                Integer.class, batchId);
        ApiException late = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> alignmentService.review(batchId,
                        new Dtos.ReviewRequest(pairs.get(0), Dtos.ReviewDecision.DOWNGRADE, "late")));
        assertEquals(HttpStatus.CONFLICT, late.status());
        assertEquals(finalConfirmed, jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_pair WHERE batch_id = ? AND review_status = 'CONFIRMED'",
                Integer.class, batchId).intValue());
    }

    @Test
    void concurrentDoubleSealIsIdempotentWithSingleSealedAt() throws Exception {
        long batchId = importGrowingBatch("REQ-CONC-DOUBLE-SEAL", 2);
        for (Long pairId : pairIds(batchId)) {
            alignmentService.review(batchId,
                    new Dtos.ReviewRequest(pairId, Dtos.ReviewDecision.CONFIRM, "r"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Dtos.SealResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return alignmentService.seal(batchId);
            }));
        }
        start.countDown();
        Dtos.SealResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        Dtos.SealResponse second = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两个并发封板都幂等成功，且封板时间一致（只有一个真正执行了封板）
        assertEquals("SEALED", first.status());
        assertEquals("SEALED", second.status());
        assertEquals(first.sealedAt(), second.sealedAt());
        assertEquals(first.sealedAt(), jdbc.queryForObject(
                "SELECT sealed_at FROM alignment_batch WHERE id = ?", java.sql.Timestamp.class, batchId)
                .toInstant());
    }

    @Test
    void concurrentReviewOnSamePairYieldsSingleWinner() throws Exception {
        long batchId = importGrowingBatch("REQ-CONC-SAME-PAIR", 1);
        long pairId = pairIds(batchId).get(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    alignmentService.review(batchId,
                            new Dtos.ReviewRequest(pairId, Dtos.ReviewDecision.CONFIRM, "r"));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 同一匹配对只允许一个复核结论落库
        assertEquals(1, success.get());
        assertEquals(1, conflict.get());
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT review_status FROM match_pair WHERE id = ?", String.class, pairId));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM match_pair WHERE id = ?",
                Long.class, pairId) == 1);
    }
}
