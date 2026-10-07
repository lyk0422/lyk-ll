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
import com.example.starter.cems.MaintenanceTypes.LabReportRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewView;
import com.example.starter.cems.MaintenanceTypes.ReplacementConfirmRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementSubmitRequest;

/**
 * 检修替代 H2（MODE=MySQL）真实事务与并发边界测试：
 * 同 expectedVersion 并发提交替代唯一受理；替代复核并发唯一受理；
 * 替代确认、补机测与日结封账竞争时，同小时绝不计两次、不混用版本，冻结快照必为某一一致序列化状态。
 * 真实线程＋CyclicBarrier 协调，断言最终数据。
 */
@SpringBootTest
class MaintenanceConcurrencyTest {

    private static final String CODE = "OUT-MTC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private MaintenanceService service;
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
    }

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private void permit() {
        service.upsertPermit(new MaintenancePermitRequest("mcc-permit", CODE, 0,
                utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "zhang", "wang"));
    }

    private void labReport(String reportNo, String hourUtc) {
        Instant h = utc(hourUtc);
        service.registerLabReport(new LabReportRequest(
                "mcc-lr-" + reportNo, CODE, reportNo, h, h.plusSeconds(3600)));
    }

    private void machineHour(String hourUtc, String value) {
        cemsService.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.ofInstant(utc(hourUtc), java.time.ZoneOffset.UTC), new BigDecimal(value)));
    }

    @Test
    void concurrentReplacementSubmitWithSameExpectedVersionAcceptsOnlyOne() {
        permit();
        labReport("MCC-R9", "2026-09-26T09:00:00Z");
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
                    service.submitReplacement(new ReplacementSubmitRequest(
                            "mcc-sub-" + tag, CODE, utc("2026-09-26T09:00:00Z"),
                            new BigDecimal("20"), "MCC-R9", "li", 0));
                    accepted.incrementAndGet();
                } catch (ConflictException e) {
                    rejected.incrementAndGet();
                }
            };
            Future<?> f1 = pool.submit(task);
            Future<?> f2 = pool.submit(task);
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get(), "同 expectedVersion 并发提交替代仅一个受理");
        assertEquals(1, rejected.get(), "另一并发必须 409");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement_hour WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void concurrentConfirmOfSamePendingVersionAcceptsOnlyOne() throws Exception {
        permit();
        labReport("MCC-C9", "2026-09-26T09:00:00Z");
        service.submitReplacement(new ReplacementSubmitRequest(
                "mcc-sub", CODE, utc("2026-09-26T09:00:00Z"),
                new BigDecimal("20"), "MCC-C9", "li", 0));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Runnable task = () -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    String tag = Thread.currentThread().getName();
                    service.confirmReplacement(new ReplacementConfirmRequest(
                            "mcc-conf-" + tag, CODE, utc("2026-09-26T09:00:00Z"), "zhao"));
                    accepted.incrementAndGet();
                } catch (Exception e) {
                    // 409（条件更新未命中）或 404（待复核已被对方消费）
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
        assertEquals(1, accepted.get(), "并发复核同一待办版本仅一个受理");
        assertEquals(1, rejected.get());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement_hour WHERE outlet_code = ? AND status = 'EFFECTIVE'",
                Long.class, CODE));
    }

    @Test
    void concurrentFreezeAndReplacementConfirmYieldOneConsistentSnapshot() throws Exception {
        permit();
        machineHour("2026-09-26T08:00:00Z", "12");
        labReport("MCC-F9", "2026-09-26T09:00:00Z");
        service.submitReplacement(new ReplacementSubmitRequest(
                "mcc-sub9", CODE, utc("2026-09-26T09:00:00Z"),
                new BigDecimal("20"), "MCC-F9", "li", 0));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirmFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.confirmReplacement(new ReplacementConfirmRequest(
                        "mcc-confirm", CODE, utc("2026-09-26T09:00:00Z"), "zhao"));
            });
            Future<MaintenanceReviewView> freezeFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.freezeReview(new MaintenanceReviewRequest("mcc-freeze", CODE, DAY));
            });
            confirmFuture.get(20, TimeUnit.SECONDS);
            MaintenanceReviewView review = freezeFuture.get(20, TimeUnit.SECONDS);

            // 封账在先：09 时仍待复核，仅 08 时机测计入；复核落封账后形成独立调差（旧=缺失）。
            boolean freezeFirst = review.countedCount() == 1
                    && review.effectiveAvg().compareTo(new BigDecimal("12.0000")) == 0
                    && review.missingCount() == 23 && review.items().size() == 1
                    && "MACHINE".equals(review.items().get(0).source());
            // 复核在先：08 机测 12 + 09 有效替代 20 计入。
            boolean confirmFirst = review.countedCount() == 2
                    && review.effectiveAvg().compareTo(new BigDecimal("16.0000")) == 0
                    && review.missingCount() == 22 && review.items().size() == 2
                    && "MACHINE".equals(review.items().get(0).source())
                    && "REPLACEMENT".equals(review.items().get(1).source())
                    && review.items().get(1).replacementVersion() == 1;
            assertTrue(freezeFirst || confirmFirst,
                    "封账与复核竞争必须落到一致快照，实际: avg=" + review.effectiveAvg()
                            + " counted=" + review.countedCount() + " missing=" + review.missingCount()
                            + " items=" + review.items().size());

            long adjustments = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_adjustment WHERE outlet_code = ?", Long.class, CODE);
            if (freezeFirst) {
                assertEquals(1L, adjustments, "封账在先时复核落封账后，须形成 1 条独立调差");
            } else {
                assertEquals(0L, adjustments, "复核在先时不产生调差");
            }
        } finally {
            pool.shutdownNow();
        }
        // 同小时不重复：冻结逐小时选择最多每小时一条；替代版本最终生效。
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_review_item WHERE outlet_code = ? AND hour_utc = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 09:00:00")));
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? AND version = 1",
                String.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 09:00:00")));
    }

    @Test
    void concurrentFreezeAndMachineBackfillNeverDoubleCountOrMixVersion() throws Exception {
        permit();
        labReport("MCC-B9", "2026-09-26T09:00:00Z");
        service.submitReplacement(new ReplacementSubmitRequest(
                "mcc-sub9b", CODE, utc("2026-09-26T09:00:00Z"),
                new BigDecimal("20"), "MCC-B9", "li", 0));
        service.confirmReplacement(new ReplacementConfirmRequest(
                "mcc-confirm9b", CODE, utc("2026-09-26T09:00:00Z"), "zhao"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> backfillFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                machineHour("2026-09-26T09:00:00Z", "30");
                return null;
            });
            Future<MaintenanceReviewView> freezeFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.freezeReview(new MaintenanceReviewRequest("mcc-freeze-b", CODE, DAY));
            });
            backfillFuture.get(20, TimeUnit.SECONDS);
            MaintenanceReviewView review = freezeFuture.get(20, TimeUnit.SECONDS);

            // 补机测在先：封账按机测 30 计，替代已失效，无调差。
            boolean backfillFirst = review.countedCount() == 1
                    && review.effectiveAvg().compareTo(new BigDecimal("30.0000")) == 0
                    && "MACHINE".equals(review.items().get(0).source())
                    && review.items().get(0).replacementVersion() == null;
            // 封账在先：冻结替代 v1 的 20，补机测落封账后形成 1 条调差，冻结均值不改写。
            boolean freezeFirst = review.countedCount() == 1
                    && review.effectiveAvg().compareTo(new BigDecimal("20.0000")) == 0
                    && "REPLACEMENT".equals(review.items().get(0).source())
                    && review.items().get(0).replacementVersion() == 1;
            assertTrue(backfillFirst || freezeFirst,
                    "封账与补机测竞争必须落到一致快照，实际: avg=" + review.effectiveAvg()
                            + " source=" + review.items().get(0).source());

            long adjustments = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_adjustment WHERE outlet_code = ?", Long.class, CODE);
            if (backfillFirst) {
                assertEquals(0L, adjustments);
            } else {
                assertEquals(1L, adjustments, "封账在先时补机测须形成 1 条独立调差");
                MapLike adj = readAdjustment();
                assertEquals("MACHINE_BACKFILL", adj.changeType);
                assertEquals("REPLACEMENT", adj.oldSource);
                assertEquals(0, new BigDecimal("20").compareTo(adj.oldValue));
                assertEquals("MACHINE", adj.newSource);
                assertEquals(0, new BigDecimal("30").compareTo(adj.newValue));
            }
        } finally {
            pool.shutdownNow();
        }
        // 同小时绝不计两次：冻结明细仅一条；机测读数仅一条；替代最终失效。
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_review_item WHERE outlet_code = ? AND hour_utc = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 09:00:00")));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_reading WHERE outlet_code = ? AND period_type = 'HOUR' AND period_start = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 09:00:00")));
        assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT status FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? AND version = 1",
                String.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 09:00:00")));
    }

    private MapLike readAdjustment() {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM maintenance_adjustment WHERE outlet_code = ?", CODE);
        java.util.Map<String, Object> row = rows.get(0);
        MapLike m = new MapLike();
        m.changeType = (String) row.get("change_type");
        m.oldSource = (String) row.get("old_source");
        m.newSource = (String) row.get("new_source");
        m.oldValue = row.get("old_value") == null ? null : new BigDecimal(row.get("old_value").toString());
        m.newValue = new BigDecimal(row.get("new_value").toString());
        return m;
    }

    private static final class MapLike {
        String changeType;
        String oldSource;
        String newSource;
        BigDecimal oldValue;
        BigDecimal newValue;
    }
}
