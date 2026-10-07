package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.SubstituteTypes.FreezeRequest;
import com.example.starter.cems.SubstituteTypes.FreezeView;
import com.example.starter.cems.SubstituteTypes.LabReportRequest;
import com.example.starter.cems.SubstituteTypes.PermitConfirmRequest;
import com.example.starter.cems.SubstituteTypes.PermitUpsertRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewResult;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitResult;

/**
 * 检修替代监测 H2（MODE=MySQL）真实事务与并发边界测试：
 * 同键并发回放唯一受理、同 expectedVersion 并发更正唯一受理、
 * 替代复核/补机测与日结冻结竞争时同小时不计两次、不混用版本。
 * 真实线程＋CyclicBarrier 协调，断言最终数据。
 */
@SpringBootTest
class SubstituteConcurrencyTest {

    private static final String CODE = "OUT-SC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final Instant H08 = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private SubstituteService service;
    @Autowired
    private CemsService cemsService;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(cemsService, CODE, "50", "10");
        service.upsertPermit(new PermitUpsertRequest("cc-p", CODE, 0,
                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"), "creator"));
        service.confirmPermit(new PermitConfirmRequest("cc-p-c1", CODE, 1, "张三"));
        service.confirmPermit(new PermitConfirmRequest("cc-p-c2", CODE, 1, "李四"));
        service.registerReport(new LabReportRequest("cc-rpt", "RPT-1", CODE, H08));
    }

    private static SubstituteSubmitRequest submitReq(String requestId, String value) {
        return new SubstituteSubmitRequest(requestId, CODE, H08,
                new BigDecimal(value), "RPT-1", "submitter", 0);
    }

    @Test
    void concurrentSameRequestIdSubmitReplaysSingleResult() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SubstituteSubmitResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.submitSubstitute(submitReq("cc-sub", "8"));
            });
            Future<SubstituteSubmitResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.submitSubstitute(submitReq("cc-sub", "8"));
            });
            assertEquals(1, f1.get(20, TimeUnit.SECONDS).version());
            assertEquals(1, f2.get(20, TimeUnit.SECONDS).version(), "并发同键同参必须回放首次结果");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM substitute_hour WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc-sub'", Long.class));
    }

    @Test
    void concurrentSubmitWithSameExpectedVersionAcceptsOnlyOne() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Runnable task = () -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                String tag = Thread.currentThread().getName();
                try {
                    service.submitSubstitute(submitReq("cc-s-" + tag, "8"));
                    accepted.incrementAndGet();
                } catch (ConflictException e) {
                    rejected.incrementAndGet();
                }
            };
            Future<?> f1 = pool.submit(task);
            Future<?> f2 = pool.submit(task);
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get(), "同 expectedVersion 并发提交仅一个受理");
        assertEquals(1, rejected.get(), "另一并发必须 409 冲突");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM substitute_hour WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void concurrentReviewAndFreezeNeverDoubleCountOrMixVersions() throws Exception {
        service.submitSubstitute(submitReq("cc-pre", "12"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 竞争：替代复核确认 vs 当日日结冻结
            Future<SubstituteReviewResult> reviewFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.reviewSubstitute(
                        new SubstituteReviewRequest("cc-race-review", CODE, H08, 1, "reviewer"));
            });
            Future<FreezeView> freezeFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.freeze(new FreezeRequest("cc-race-freeze", CODE, DAY));
            });
            SubstituteReviewResult review = reviewFuture.get(20, TimeUnit.SECONDS);
            FreezeView frozen = freezeFuture.get(20, TimeUnit.SECONDS);

            // 序列化语义：复核先则冻结含替代来源；冻结先则复核落为独立调差，二者必居其一
            boolean reviewFirst = !review.frozen()
                    && frozen.substituteCount() == 1 && frozen.machineCount() == 0
                    && frozen.avgValue().compareTo(new BigDecimal("12.0000")) == 0;
            boolean freezeFirst = review.frozen() && review.adjustmentRecorded()
                    && frozen.substituteCount() == 0 && frozen.avgValue() == null;
            assertTrue(reviewFirst || freezeFirst,
                    "复核与冻结竞争不得把同小时计两次或混用版本，实际: review.frozen=" + review.frozen()
                            + " substituteCount=" + frozen.substituteCount()
                            + " avg=" + frozen.avgValue());
            if (freezeFirst) {
                assertEquals(1, service.listAdjustments(CODE, DAY).size(),
                        "冻结后复核必须形成独立调差");
            }
        } finally {
            pool.shutdownNow();
        }
        // 无论竞争次序，冻结快照每整点槽位唯一、替代台账仅一个当前版本
        assertEquals(24L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM effective_freeze_item WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM substitute_hour WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void concurrentMachineSupplementAndFreezeYieldConsistentSnapshot() throws Exception {
        service.submitSubstitute(submitReq("cc-pre", "12"));
        service.reviewSubstitute(new SubstituteReviewRequest("cc-rv", CODE, H08, 1, "reviewer"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 竞争：补 08 时机测读数（本地 16 时）vs 当日日结冻结
            Future<?> machineFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                cemsService.reportHourly(new HourlyReadingRequest(
                        CODE, LocalDateTime.of(2026, 9, 26, 16, 0), new BigDecimal("6")));
                return null;
            });
            Future<FreezeView> freezeFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.freeze(new FreezeRequest("cc-race-freeze2", CODE, DAY));
            });
            machineFuture.get(20, TimeUnit.SECONDS);
            FreezeView frozen = freezeFuture.get(20, TimeUnit.SECONDS);

            // 机测先则冻结含机测来源且替代已失效；冻结先则快照为替代来源且补机测落为调差
            boolean machineFirst = frozen.machineCount() == 1 && frozen.substituteCount() == 0
                    && frozen.avgValue().compareTo(new BigDecimal("6.0000")) == 0;
            boolean freezeFirst = frozen.machineCount() == 0 && frozen.substituteCount() == 1
                    && frozen.avgValue().compareTo(new BigDecimal("12.0000")) == 0;
            assertTrue(machineFirst || freezeFirst,
                    "补机测与冻结竞争不得混用版本，实际: machine=" + frozen.machineCount()
                            + " substitute=" + frozen.substituteCount() + " avg=" + frozen.avgValue());
            List<?> adjustments = service.listAdjustments(CODE, DAY);
            if (freezeFirst) {
                assertEquals(1, adjustments.size(), "冻结后补机测必须形成独立调差");
                assertEquals("EFFECTIVE", service.listSubstitutes(CODE).get(0).status(),
                        "冻结后补机测不改台账");
            } else {
                assertEquals(0, adjustments.size());
                assertEquals("INVALIDATED", service.listSubstitutes(CODE).get(0).status(),
                        "封账前补机测须令生效替代失效留证");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_reading WHERE outlet_code = ? AND period_type = 'HOUR'",
                Long.class, CODE));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM effective_freeze WHERE outlet_code = ?", Long.class, CODE));
    }
}
