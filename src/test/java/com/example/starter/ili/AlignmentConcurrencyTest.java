package com.example.starter.ili;

import com.example.starter.ili.api.ApiException;
import com.example.starter.ili.api.BatchView;
import com.example.starter.ili.api.ImportBatchRequest;
import com.example.starter.ili.api.ReadingPayload;
import com.example.starter.ili.api.ReviewRequest;
import com.example.starter.ili.api.RunPayload;
import com.example.starter.ili.persistence.MatchRepository;
import com.example.starter.ili.persistence.MatchRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试：封板与复核竞态一致性、同 requestId 并发导入幂等、并发封板幂等。
 * 使用独立 H2 内存库与真实线程并发，全部断言最终数据库状态。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ili_alignment_race;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class AlignmentConcurrencyTest {

    @Autowired
    private AlignmentService service;
    @Autowired
    private MatchRepository matchRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM match_pair");
        jdbc.update("DELETE FROM defect_reading");
        jdbc.update("DELETE FROM alignment_batch");
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP ALL OBJECTS");
    }

    private static ImportBatchRequest growingBatch(String requestId) {
        return new ImportBatchRequest(requestId, "SEG-RACE",
                new RunPayload("R1", LocalDate.of(2020, 1, 1), List.of(
                        new ReadingPayload("o1", new BigDecimal("100.0"), new BigDecimal("10")))),
                new RunPayload("R2", LocalDate.of(2024, 1, 1), List.of(
                        new ReadingPayload("n1", new BigDecimal("100.0"), new BigDecimal("30")))));
    }

    /**
     * 并发提交同 requestId 同参数导入：全部成功且返回同一批次，库中仅一条批次。
     */
    @Test
    @Timeout(60)
    void concurrentDuplicateImportYieldsSingleBatch() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<BatchView>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return service.importBatch(growingBatch("req-race-import"));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();

            long firstId = -1;
            for (Future<BatchView> f : futures) {
                long id = f.get(30, TimeUnit.SECONDS).id();
                if (firstId < 0) {
                    firstId = id;
                }
                assertEquals(firstId, id, "同键同参并发导入必须返回首次结果");
            }
            Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM alignment_batch", Integer.class);
            Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM match_pair", Integer.class);
            assertEquals(1, batches);
            assertEquals(1, matches);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 并发封板与复核：结果必须一致——封板成功当且仅当复核已提交；
     * 绝不出现“已封板但增长缺陷仍待复核”或封板后写入复核结论。
     */
    @Test
    @Timeout(120)
    void sealAndReviewRaceIsConsistent() throws Exception {
        for (int round = 0; round < 5; round++) {
            cleanTables();
            BatchView batch = service.importBatch(growingBatch("req-race-" + round));
            long matchId = batch.matches().get(0).id();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger sealConflicts = new AtomicInteger();
            AtomicInteger reviewConflicts = new AtomicInteger();
            try {
                Callable<String> sealTask = () -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        service.seal(batch.id());
                        return "SEALED";
                    } catch (ApiException e) {
                        assertEquals(HttpStatus.CONFLICT, e.status());
                        sealConflicts.incrementAndGet();
                        return "SEAL_CONFLICT";
                    }
                };
                Callable<String> reviewTask = () -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        service.review(batch.id(), new ReviewRequest(List.of(
                                new ReviewRequest.Item(matchId, ReviewRequest.Action.CONFIRM, null, "reviewer-a"))));
                        return "REVIEWED";
                    } catch (ApiException e) {
                        assertEquals(HttpStatus.CONFLICT, e.status());
                        reviewConflicts.incrementAndGet();
                        return "REVIEW_CONFLICT";
                    }
                };
                Future<String> sealFuture = pool.submit(sealTask);
                Future<String> reviewFuture = pool.submit(reviewTask);
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                go.countDown();
                String sealOutcome = sealFuture.get(30, TimeUnit.SECONDS);
                String reviewOutcome = reviewFuture.get(30, TimeUnit.SECONDS);

                BatchView after = service.getBatch(batch.id());
                MatchRow match = matchRepository.findById(matchId).orElseThrow();

                if ("SEALED".equals(sealOutcome)) {
                    // 封板成功 ⇒ 复核必须先已提交（否则封板会被 409 拒绝）
                    assertEquals("REVIEWED", reviewOutcome);
                    assertEquals("SEALED", after.status());
                    assertEquals("CONFIRMED", match.reviewStatus().name());
                } else {
                    // 封板被拒 ⇒ 批次保持 OPEN，复核结论正常落库
                    assertEquals("OPEN", after.status());
                    assertEquals("CONFIRMED", match.reviewStatus().name());
                }
                // 不变式：封板批次绝不存在待复核的增长/严重缺陷
                if ("SEALED".equals(after.status())) {
                    assertNotEquals("PENDING", match.reviewStatus().name());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * 并发重复封板：均幂等成功，批次仅一条且 sealedAt 一致。
     */
    @Test
    @Timeout(60)
    void concurrentSealIsIdempotent() throws Exception {
        BatchView batch = service.importBatch(growingBatch("req-race-seal"));
        long matchId = batch.matches().get(0).id();
        service.review(batch.id(), new ReviewRequest(List.of(
                new ReviewRequest.Item(matchId, ReviewRequest.Action.CONFIRM, null, "reviewer-a"))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<BatchView> sealTask = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return service.seal(batch.id());
            };
            Future<BatchView> s1 = pool.submit(sealTask);
            Future<BatchView> s2 = pool.submit(sealTask);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            BatchView b1 = s1.get(30, TimeUnit.SECONDS);
            BatchView b2 = s2.get(30, TimeUnit.SECONDS);
            assertEquals("SEALED", b1.status());
            assertEquals("SEALED", b2.status());
            assertEquals(b1.sealedAt(), b2.sealedAt());
            Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM alignment_batch", Integer.class);
            assertEquals(1, batches);
        } finally {
            pool.shutdownNow();
        }
    }
}
