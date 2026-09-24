package com.example.starter.ili;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ili.BatchSubmitRequest.DefectReading;
import com.example.starter.ili.BatchSubmitRequest.RunDefects;
import com.example.starter.ili.ReviewRequest.ReviewAction;

/**
 * 基于 H2（MODE=MySQL）的服务集成测试：覆盖幂等提交、整体回滚、
 * 分级与复核、封板终态及封板/复核并发竞态。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ili_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlignmentServiceTest {

    /**
     * 测试时钟：固定时刻，保证时间相关断言可重复。
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-09-25T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private AlignmentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM defect_match");
        jdbc.update("DELETE FROM defect_reading");
        jdbc.update("DELETE FROM alignment_batch");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    private static DefectReading defect(String ref, String mileage, String depth) {
        return new DefectReading(ref, new BigDecimal(mileage), new BigDecimal(depth));
    }

    /** 构造双回合请求：旧回合 2023-01-01，新回合 2024-01-01，间隔恰好 365 天即 1 年。 */
    private static BatchSubmitRequest request(String requestId,
                                              List<DefectReading> oldDefects,
                                              List<DefectReading> newDefects) {
        return new BatchSubmitRequest(requestId, "SEG-A",
                new RunDefects("RUN-2023", LocalDate.of(2023, 1, 1), oldDefects),
                new RunDefects("RUN-2024", LocalDate.of(2024, 1, 1), newDefects));
    }

    @Test
    void 提交后完成对齐分级并标记未匹配读数() {
        BatchView view = service.submit(request("req-align",
                List.of(defect("O1", "100.000", "10"), defect("O2", "300.000", "8")),
                List.of(defect("N1", "100.500", "15"), defect("N2", "500.000", "6"))));

        assertEquals(BatchStatus.OPEN, view.status());
        assertEquals(1, view.matches().size());
        BatchView.MatchView match = view.matches().get(0);
        assertEquals("O1", match.oldDefectRef());
        assertEquals("N1", match.newDefectRef());
        assertEquals(0, new BigDecimal("0.500").compareTo(match.mileageDiffM()));
        // 深度差 5% / 1 年 = 5%/年 → 增长
        assertEquals(Grade.GROWING, match.grade());
        assertEquals(ReviewStatus.PENDING, match.reviewStatus());

        assertEquals(2, view.unmatchedReadings().size());
        assertTrue(view.unmatchedReadings().stream().anyMatch(
                r -> r.defectRef().equals("N2") && r.outcome() == MatchOutcome.NEW_FOUND));
        assertTrue(view.unmatchedReadings().stream().anyMatch(
                r -> r.defectRef().equals("O2") && r.outcome() == MatchOutcome.DISAPPEARED));
    }

    @Test
    void 分级阈值在数据库结果中生效() {
        BatchView view = service.submit(request("req-grade",
                List.of(defect("O1", "100", "10"), defect("O2", "200", "10"),
                        defect("O3", "300", "10"), defect("O4", "400", "10")),
                List.of(defect("N1", "100", "14.9"), defect("N2", "200", "15"),
                        defect("N3", "300", "24.9"), defect("N4", "400", "25"))));

        assertEquals(4, view.matches().size());
        assertGrade(view, "N1", Grade.STABLE);
        assertGrade(view, "N2", Grade.GROWING);
        assertGrade(view, "N3", Grade.GROWING);
        assertGrade(view, "N4", Grade.SEVERE);
    }

    private static void assertGrade(BatchView view, String newRef, Grade expected) {
        BatchView.MatchView match = view.matches().stream()
                .filter(m -> m.newDefectRef().equals(newRef))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, match.grade(), newRef);
    }

    @Test
    void 同键同参重放返回首次结果且不重复写入() {
        var req = request("req-idem",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100.2", "20")));
        BatchView first = service.submit(req);
        BatchView replay = service.submit(req);

        assertEquals(first.batchId(), replay.batchId());
        assertEquals(first.createdAt(), replay.createdAt());
        assertEquals(1, countRows("alignment_batch"));
        assertEquals(2, countRows("defect_reading"));
        assertEquals(1, countRows("defect_match"));
    }

    @Test
    void 同键异参返回409且不写入部分结果() {
        service.submit(request("req-conflict",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100.2", "20"))));

        ApiException e = assertThrows(ApiException.class, () -> service.submit(request("req-conflict",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100.2", "25")))));
        assertEquals(HttpStatus.CONFLICT, e.status());

        assertEquals(1, countRows("alignment_batch"));
        assertEquals(2, countRows("defect_reading"));
        assertEquals(1, countRows("defect_match"));
    }

    @Test
    void 导入失败时整体回滚不残留部分数据() {
        // 超长缺陷编号绕过服务层校验、触发数据库列长约束，制造导入中途失败。
        String tooLongRef = "X".repeat(100);
        var req = request("req-rollback",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100.2", "20"), defect(tooLongRef, "200", "30")));

        assertThrows(DataIntegrityViolationException.class, () -> service.submit(req));

        assertEquals(0, countRows("alignment_batch"));
        assertEquals(0, countRows("defect_reading"));
        assertEquals(0, countRows("defect_match"));
    }

    @Test
    void 复核确认与降级写入结论() {
        BatchView view = service.submit(request("req-review",
                List.of(defect("O1", "100", "10"), defect("O2", "200", "10")),
                List.of(defect("N1", "100", "25"), defect("N2", "200", "17"))));
        long severeId = matchId(view, "N1");
        long growingId = matchId(view, "N2");

        BatchView confirmed = service.review(view.batchId(), severeId,
                new ReviewRequest(ReviewAction.CONFIRM, null, "reviewer-a"));
        BatchView.MatchView severe = confirmed.matches().stream()
                .filter(m -> m.matchId() == severeId).findFirst().orElseThrow();
        assertEquals(ReviewStatus.CONFIRMED, severe.reviewStatus());
        assertEquals(Grade.SEVERE, severe.finalGrade());
        assertEquals("reviewer-a", severe.reviewedBy());
        assertNotNull(severe.reviewedAt());

        BatchView downgraded = service.review(view.batchId(), growingId,
                new ReviewRequest(ReviewAction.DOWNGRADE, Grade.STABLE, "reviewer-b"));
        BatchView.MatchView growing = downgraded.matches().stream()
                .filter(m -> m.matchId() == growingId).findFirst().orElseThrow();
        assertEquals(ReviewStatus.DOWNGRADED, growing.reviewStatus());
        assertEquals(Grade.STABLE, growing.finalGrade());
    }

    @Test
    void 稳定缺陷无需复核且降级目标必须更低() {
        BatchView view = service.submit(request("req-review-invalid",
                List.of(defect("O1", "100", "10"), defect("O2", "200", "10")),
                List.of(defect("N1", "100", "12"), defect("N2", "200", "16"))));
        long stableId = matchId(view, "N1");
        long growingId = matchId(view, "N2");

        ApiException stable = assertThrows(ApiException.class, () -> service.review(
                view.batchId(), stableId, new ReviewRequest(ReviewAction.CONFIRM, null, "r")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, stable.status());

        ApiException badTarget = assertThrows(ApiException.class, () -> service.review(
                view.batchId(), growingId,
                new ReviewRequest(ReviewAction.DOWNGRADE, Grade.SEVERE, "r")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badTarget.status());
    }

    @Test
    void 封板为终态且重复封板幂等成功() {
        BatchView view = service.submit(request("req-seal",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100", "20"))));

        BatchView sealed = service.seal(view.batchId());
        assertEquals(BatchStatus.SEALED, sealed.status());
        assertNotNull(sealed.sealedAt());

        BatchView resealed = service.seal(view.batchId());
        assertEquals(BatchStatus.SEALED, resealed.status());
        assertEquals(sealed.sealedAt(), resealed.sealedAt());

        // 封板后迟到复核拒绝且不改数据
        long matchId = matchId(sealed, "N1");
        ApiException e = assertThrows(ApiException.class, () -> service.review(
                view.batchId(), matchId, new ReviewRequest(ReviewAction.CONFIRM, null, "late")));
        assertEquals(HttpStatus.CONFLICT, e.status());

        BatchView after = service.getBatch(view.batchId());
        BatchView.MatchView match = after.matches().get(0);
        assertEquals(ReviewStatus.PENDING, match.reviewStatus());
        assertNull(match.reviewedBy());
    }

    @Test
    void 并发同键提交只写入一批次() throws Exception {
        var req = request("req-concurrent-submit",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100.2", "20")));
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<BatchView>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return service.submit(req);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            long firstId = -1;
            for (Future<BatchView> f : futures) {
                BatchView view = f.get(30, TimeUnit.SECONDS);
                if (firstId < 0) {
                    firstId = view.batchId();
                }
                assertEquals(firstId, view.batchId());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, countRows("alignment_batch"));
        assertEquals(2, countRows("defect_reading"));
        assertEquals(1, countRows("defect_match"));
    }

    @Test
    void 封板与复核并发竞态结果一致() throws Exception {
        BatchView view = service.submit(request("req-race",
                List.of(defect("O1", "100", "10")),
                List.of(defect("N1", "100", "20"))));
        long matchId = matchId(view, "N1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> reviewOutcome = new AtomicReference<>();
        try {
            Future<BatchView> sealFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return service.seal(view.batchId());
            });
            Future<?> reviewFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                try {
                    reviewOutcome.set(service.review(view.batchId(), matchId,
                            new ReviewRequest(ReviewAction.CONFIRM, null, "racer")));
                } catch (ApiException e) {
                    reviewOutcome.set(e);
                }
                return null;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            BatchView sealed = sealFuture.get(30, TimeUnit.SECONDS);
            reviewFuture.get(30, TimeUnit.SECONDS);
            assertEquals(BatchStatus.SEALED, sealed.status());
        } finally {
            pool.shutdownNow();
        }

        // 一致性：复核要么先于封板完整写入，要么被 409 拒绝且数据库无结论。
        BatchView.MatchView match = service.getBatch(view.batchId()).matches().get(0);
        Object outcome = reviewOutcome.get();
        if (outcome instanceof ApiException e) {
            assertEquals(HttpStatus.CONFLICT, e.status());
            assertEquals(ReviewStatus.PENDING, match.reviewStatus());
            assertNull(match.reviewedBy());
        } else {
            assertEquals(ReviewStatus.CONFIRMED, match.reviewStatus());
            assertEquals("racer", match.reviewedBy());
        }
        assertEquals(BatchStatus.SEALED, service.getBatch(view.batchId()).status());
    }

    private long matchId(BatchView view, String newRef) {
        return view.matches().stream()
                .filter(m -> m.newDefectRef().equals(newRef))
                .findFirst()
                .orElseThrow()
                .matchId();
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
