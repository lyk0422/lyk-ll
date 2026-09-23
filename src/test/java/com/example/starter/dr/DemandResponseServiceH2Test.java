package com.example.starter.dr;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.dr.domain.Rows;
import com.example.starter.dr.repository.DrRepository;
import com.example.starter.dr.service.DemandResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求响应服务在真实 H2（MODE=MySQL）上的事务/并发边界测试。
 * 不使用 mock：区间版本、唯一约束、事务回滚与真实并发均由数据库验证。
 */
@SpringBootTest
class DemandResponseServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T01:00:00Z");

    @Autowired
    private DemandResponseService service;
    @Autowired
    private DrRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM adjustment_advice");
        jdbc.update("DELETE FROM settlement_interval");
        jdbc.update("DELETE FROM settlement");
        jdbc.update("DELETE FROM freeze_request");
        jdbc.update("DELETE FROM curve_point_current");
        jdbc.update("DELETE FROM curve_point_history");
        jdbc.update("DELETE FROM dr_enrollment");
        jdbc.update("DELETE FROM dr_event");
    }

    private ApiModels.CreateEventRequest eventReq(String id, Instant start, Instant end, int minutes) {
        return new ApiModels.CreateEventRequest(id, start, end, minutes);
    }

    private void seedFullCurves(String eventId, String meterId,
                                List<Integer> baseline, List<Integer> actual) {
        service.writeCurve(eventId, meterId, DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(points(baseline, null)));
        service.writeCurve(eventId, meterId, DemandResponseService.TYPE_ACTUAL,
                new ApiModels.CurveWriteRequest(points(actual, null)));
    }

    private List<ApiModels.CurvePointRequest> points(List<Integer> values, Integer expectedVersion) {
        return java.util.stream.IntStream.range(0, values.size())
                .mapToObj(i -> new ApiModels.CurvePointRequest(
                        i, new BigDecimal(values.get(i)), expectedVersion))
                .toList();
    }

    private ApiModels.FreezeRequest freezeRequest(String requestId, String meterId,
                                                  int eventVersion, int bVersion, int aVersion) {
        return new ApiModels.FreezeRequest(requestId, eventVersion,
                List.of(new ApiModels.FreezeMeterVersion(meterId, bVersion, aVersion)));
    }

    // ---------- 半开区间与粒度 ----------

    @Test
    void halfOpenIntervalProducesCorrectIntervalCount() {
        ApiModels.EventResponse event = service.createEvent(eventReq("E1", T0, T1, 30));
        assertEquals(2, event.intervalCount(), "[00:00,01:00) 按 30 分钟应为 2 个半开区间");
        assertEquals(1, event.version());
    }

    @Test
    void intervalLengthMustDivideDuration() {
        assertThrows(DrValidationException.class,
                () -> service.createEvent(eventReq("EBAD", T0, Instant.parse("2026-01-01T00:45:00Z"), 30)));
    }

    @Test
    void endTouchingEventsAreNotOverlapButIntersectingAre() {
        service.createEvent(eventReq("E1", T0, T1, 30));
        service.enrollMeter("E1", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));

        // 端点相接：[01:00,02:00) 与 [00:00,01:00) 不算重叠
        service.createEvent(eventReq("E2", T1, Instant.parse("2026-01-01T02:00:00Z"), 30));
        ApiModels.EventResponse touched = service.enrollMeter(
                "E2", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        assertEquals(1, touched.enrollments().size());

        // 内点相交：[00:30,01:30) 重叠
        service.createEvent(eventReq("E3", Instant.parse("2026-01-01T00:30:00Z"),
                Instant.parse("2026-01-01T01:30:00Z"), 30));
        assertThrows(DrConflictException.class, () -> service.enrollMeter(
                "E3", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1")));

        // 完全相同区间也重叠
        service.createEvent(eventReq("E4", T0, T1, 30));
        assertThrows(DrConflictException.class, () -> service.enrollMeter(
                "E4", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1")));
    }

    @Test
    void duplicateEnrollmentInSameEventConflicts() {
        service.createEvent(eventReq("E1", T0, T1, 30));
        service.enrollMeter("E1", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        assertThrows(DrConflictException.class, () -> service.enrollMeter(
                "E1", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("60"), "B2")));
    }

    // ---------- 曲线版本与区间边界 ----------

    @Test
    void firstWriteMustNotCarryVersionAndCorrectionMustCarryPrevious() {
        service.createEvent(eventReq("E1", T0, T1, 30));
        service.enrollMeter("E1", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));

        // 首次写入携带版本号 → 冲突
        assertThrows(DrConflictException.class, () -> service.writeCurve(
                "E1", "M1", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("100"), 1)))));

        // 正常首写
        ApiModels.CurveWriteResponse written = service.writeCurve(
                "E1", "M1", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("100"), null))));
        assertEquals(1, written.dataVersion());

        // 更正不带前版本号 → 冲突
        assertThrows(DrConflictException.class, () -> service.writeCurve(
                "E1", "M1", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("110"), null)))));

        // 更正携带错误前版本号 → 冲突
        assertThrows(DrConflictException.class, () -> service.writeCurve(
                "E1", "M1", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("110"), 7)))));

        // 正确携带前版本号 → 成功且版本递增
        ApiModels.CurveWriteResponse corrected = service.writeCurve(
                "E1", "M1", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("110"), 1))));
        assertEquals(2, corrected.dataVersion());
        Rows.CurvePointRow current = repo.findCurvePoint("E1", "M1", 0,
                DemandResponseService.TYPE_BASELINE).orElseThrow();
        assertEquals(2, current.version());
        assertBd("110", current.value());
    }

    @Test
    void intervalIndexOutOfRangeRejected() {
        service.createEvent(eventReq("E1", T0, T1, 30));
        service.enrollMeter("E1", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        assertThrows(DrValidationException.class, () -> service.writeCurve(
                "E1", "M1", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(2, new BigDecimal("100"), null)))));
    }

    // ---------- 缺值 PENDING 与整笔回滚 ----------

    @Test
    void missingValueFreezesAsPendingAndRollsBackWholeSettlement() {
        service.createEvent(eventReq("E1", T0, T1, 30));
        service.enrollMeter("E1", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        service.enrollMeter("E1", new ApiModels.EnrollMeterRequest("M2", new BigDecimal("50"), "B1"));
        // 两块表各两个区间，但 M2 的 idx1 实测缺失
        seedFullCurves("E1", "M1", List.of(100, 90), List.of(70, 90));
        service.writeCurve("E1", "M2", DemandResponseService.TYPE_BASELINE,
                new ApiModels.CurveWriteRequest(points(List.of(100, 90), null)));
        service.writeCurve("E1", "M2", DemandResponseService.TYPE_ACTUAL,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("70"), null))));

        int eventVersion = service.getEvent("E1").version();
        DrUnprocessableException ex = assertThrows(DrUnprocessableException.class,
                () -> service.freeze("E1", new ApiModels.FreezeRequest("R1", eventVersion,
                        List.of(new ApiModels.FreezeMeterVersion("M1", 1, 1),
                                new ApiModels.FreezeMeterVersion("M2", 1, 1)))));
        assertTrue(ex.getMessage().contains("PENDING"));

        // 整笔失败：不得留下任何结算/区间/请求痕迹，事件未冻结
        assertEquals(0, count("SELECT COUNT(*) FROM settlement WHERE event_id='E1'"));
        assertEquals(0, count("SELECT COUNT(*) FROM settlement_interval WHERE event_id='E1'"));
        assertEquals(0, count("SELECT COUNT(*) FROM freeze_request WHERE request_id='R1'"));
        assertFalse(service.getEvent("E1").frozen());

        // 补齐缺值后可重新冻结成功
        service.writeCurve("E1", "M2", DemandResponseService.TYPE_ACTUAL,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(1, new BigDecimal("90"), null))));
        ApiModels.SettlementResponse settlement = service.freeze("E1",
                new ApiModels.FreezeRequest("R2", service.getEvent("E1").version(),
                        List.of(new ApiModels.FreezeMeterVersion("M1", 1, 1),
                                new ApiModels.FreezeMeterVersion("M2", 1, 1))));
        assertEquals(4, settlement.intervals().size());
        // M1: idx0 30 + idx1 0；M2: idx0 30 + idx1 0 → 合计 60
        assertBd("60", settlement.totalDeliveredKw());
    }

    // ---------- 交付量封顶 ----------

    @Test
    void deliveredIsCappedByCommittedPower() {
        service.createEvent(eventReq("EC", T0, T1, 30));
        // 承诺 20：idx0 实减 30 应封顶到 20；idx1 实测高于基线按 0
        service.enrollMeter("EC", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("20"), "B1"));
        seedFullCurves("EC", "M1", List.of(100, 90), List.of(70, 120));
        ApiModels.SettlementResponse settlement = service.freeze("EC",
                freezeRequest("RC", "M1", service.getEvent("EC").version(), 1, 1));
        assertBd("20", settlement.totalDeliveredKw());
        assertBd("20", settlement.intervals().get(0).deliveredKw());
        assertBd("0", settlement.intervals().get(1).deliveredKw());
    }

    // ---------- 版本冲突 ----------

    @Test
    void staleEvidenceVersionConflictsAndRollsBack() {
        service.createEvent(eventReq("EV", T0, T1, 30));
        service.enrollMeter("EV", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        seedFullCurves("EV", "M1", List.of(100, 90), List.of(70, 90));
        // 先把实测更正在 v2
        service.writeCurve("EV", "M1", DemandResponseService.TYPE_ACTUAL,
                new ApiModels.CurveWriteRequest(points(List.of(65, 90), 1)));

        int eventVersion = service.getEvent("EV").version();
        // 冻结仍声称实测版本 1 → 版本冲突整笔失败
        assertThrows(DrConflictException.class,
                () -> service.freeze("EV", freezeRequest("RV", "M1", eventVersion, 1, 1)));
        assertEquals(0, count("SELECT COUNT(*) FROM settlement WHERE event_id='EV'"));
        assertFalse(service.getEvent("EV").frozen());

        // 使用正确版本（基线1、实测2）冻结成功
        ApiModels.SettlementResponse settlement = service.freeze("EV",
                freezeRequest("RV2", "M1", service.getEvent("EV").version(), 1, 2));
        assertBd("35", settlement.totalDeliveredKw());
    }

    // ---------- 请求指纹幂等与冲突 ----------

    @Test
    void sameRequestAndFingerprintReplaysFirstResultDifferentParamsConflicts() {
        service.createEvent(eventReq("EI", T0, T1, 30));
        service.enrollMeter("EI", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        seedFullCurves("EI", "M1", List.of(100, 90), List.of(70, 90));
        int version = service.getEvent("EI").version();

        ApiModels.SettlementResponse first = service.freeze("EI",
                freezeRequest("RIDEMP", "M1", version, 1, 1));
        // 同键同完整参数指纹重放：返回首次结果
        ApiModels.SettlementResponse replay = service.freeze("EI",
                freezeRequest("RIDEMP", "M1", version, 1, 1));
        assertEquals(first.settlementId(), replay.settlementId());
        assertEquals(0, first.totalDeliveredKw().compareTo(replay.totalDeliveredKw()));
        assertEquals(first.frozenAt(), replay.frozenAt());
        assertEquals(1, count("SELECT COUNT(*) FROM settlement WHERE event_id='EI'"));

        // 同键异参（实测版本变化）→ 409
        assertThrows(DrConflictException.class,
                () -> service.freeze("EI", freezeRequest("RIDEMP", "M1", version, 1, 2)));
        // 同键异事件 → 409
        assertThrows(DrConflictException.class,
                () -> service.freeze("EOTHER", freezeRequest("RIDEMP", "M1", version, 1, 1)));
    }

    // ---------- 冻结后更正只形成调整建议 ----------

    @Test
    void correctionsAfterFreezeOnlyCreateAdjustmentAdvice() {
        service.createEvent(eventReq("EA", T0, T1, 30));
        service.enrollMeter("EA", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        seedFullCurves("EA", "M1", List.of(100, 90), List.of(70, 90));
        ApiModels.SettlementResponse frozen = service.freeze("EA",
                freezeRequest("RA", "M1", service.getEvent("EA").version(), 1, 1));
        assertBd("30", frozen.totalDeliveredKw());

        // 冻结后更正实测 idx0：70 -> 60，重算交付 40，差额 +10
        ApiModels.CurveWriteResponse corrected = service.writeCurve(
                "EA", "M1", DemandResponseService.TYPE_ACTUAL,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("60"), 1))));
        assertEquals(2, corrected.dataVersion());

        // 冻结证据不可改写
        ApiModels.SettlementResponse stillFrozen = service.getSettlement("EA");
        assertBd("30", stillFrozen.totalDeliveredKw());
        assertBd("70", stillFrozen.intervals().get(0).actualValue());
        assertEquals(1, stillFrozen.intervals().get(0).actualVersion());

        // 独立调整建议反映差额
        ApiModels.AdjustmentResponse adjustments = service.getAdjustments("EA");
        assertEquals(1, adjustments.adjustments().size());
        ApiModels.AdjustmentView advice = adjustments.adjustments().get(0);
        assertEquals(0, advice.intervalIndex());
        assertBd("30", advice.frozenDeliveredKw());
        assertBd("40", advice.revisedDeliveredKw());
        assertBd("10", advice.deltaKw());
        assertBd("10", adjustments.totalDeltaKw());

        // 再次更正（60 -> 80，重算为 20，差额 -10）更新同一建议
        service.writeCurve("EA", "M1", DemandResponseService.TYPE_ACTUAL,
                new ApiModels.CurveWriteRequest(
                        List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("80"), 2))));
        ApiModels.AdjustmentResponse again = service.getAdjustments("EA");
        assertEquals(1, again.adjustments().size());
        assertBd("-10", again.totalDeltaKw());
        assertEquals(3, again.adjustments().get(0).actualVersion());
    }

    // ---------- 真实并发：乐观更正 ----------

    @Test
    void concurrentCorrectionsWithSameExpectedVersionLeaveExactlyOneWinner() throws Exception {
        service.createEvent(eventReq("EX", T0, T1, 30));
        service.enrollMeter("EX", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        seedFullCurves("EX", "M1", List.of(100, 90), List.of(70, 90));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (int t = 0; t < 2; t++) {
            final int tag = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    service.writeCurve("EX", "M1", DemandResponseService.TYPE_ACTUAL,
                            new ApiModels.CurveWriteRequest(List.of(
                                    new ApiModels.CurvePointRequest(0, new BigDecimal(60 + tag), 1))));
                } catch (DrConflictException e) {
                    // 期望的失败分支
                } catch (Throwable e) {
                    unexpected.compareAndSet(e, e);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Rows.CurvePointRow current =
                repo.findCurvePoint("EX", "M1", 0, DemandResponseService.TYPE_ACTUAL).orElseThrow();
        assertEquals(2, current.version(), "并发更正后只允许一个赢家，版本恰为 2");
        assertNull(unexpected.get(), "不应出现非冲突异常");
        // 两个值 60/61 中恰有一个落库，失败者未写入
        assertTrue(current.value().compareTo(new BigDecimal("60")) == 0
                || current.value().compareTo(new BigDecimal("61")) == 0);
        assertEquals(2, count("SELECT COUNT(*) FROM curve_point_history"
                + " WHERE event_id='EX' AND meter_id='M1' AND interval_index=0 AND data_type='ACTUAL'"),
                "版本流水应为 v1 首写 + v2 唯一赢家，共 2 条");
    }

    // ---------- 真实并发：冻结与更正 ----------

    @Test
    void concurrentFreezeAndCorrectionNeverLeavesPartialSettlement() throws Exception {
        service.createEvent(eventReq("EC2", T0, T1, 30));
        service.enrollMeter("EC2", new ApiModels.EnrollMeterRequest("M1", new BigDecimal("50"), "B1"));
        seedFullCurves("EC2", "M1", List.of(100, 90), List.of(70, 90));
        int versionAtFreeze = service.getEvent("EC2").version();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<ApiModels.SettlementResponse> freezeResult = new AtomicReference<>();
        AtomicReference<Throwable> freezeError = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Future<?> f1 = pool.submit(() -> {
            try {
                start.await();
                freezeResult.set(service.freeze("EC2",
                        freezeRequest("RCONC", "M1", versionAtFreeze, 1, 1)));
            } catch (DrConflictException e) {
                freezeError.set(e);
            } catch (Throwable e) {
                unexpected.compareAndSet(e, e);
            }
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                start.await();
                service.writeCurve("EC2", "M1", DemandResponseService.TYPE_ACTUAL,
                        new ApiModels.CurveWriteRequest(
                                List.of(new ApiModels.CurvePointRequest(0, new BigDecimal("60"), 1))));
            } catch (DrConflictException e) {
                // 冻结先拿锁时更正不会冲突（形成调整建议）；出现冲突也属于一致结果
            } catch (Throwable e) {
                unexpected.compareAndSet(e, e);
            }
        });
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(unexpected.get() == null,
                "并发期间不应出现非预期异常: " + (unexpected.get() == null ? "" : unexpected.get()));

        int intervalRows = count("SELECT COUNT(*) FROM settlement_interval WHERE event_id='EC2'");
        if (freezeResult.get() != null) {
            // 冻结先完成：证据固定为 v1 实测量 70，合计 30；并发更正只形成调整建议
            assertBd("30", freezeResult.get().totalDeliveredKw());
            assertEquals(2, intervalRows, "冻结成功时区间证据必须完整");
            assertBd("70", service.getSettlement("EC2").intervals().get(0).actualValue());
            ApiModels.AdjustmentResponse adjustments = service.getAdjustments("EC2");
            assertEquals(1, adjustments.adjustments().size());
            assertBd("10", adjustments.totalDeltaKw());
        } else {
            // 更正先提交：证据版本已变，冻结整笔失败，无任何部分结算
            assertNotNull(freezeError.get());
            assertEquals(0, intervalRows, "冻结失败时不得留下区间证据");
            assertEquals(0, count("SELECT COUNT(*) FROM settlement WHERE event_id='EC2'"));
            assertFalse(service.getEvent("EC2").frozen());
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** BigDecimal 数值相等断言（忽略 scale，例如 30 与 30.000000）。 */
    private static void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "期望 " + expected + "，实际 " + actual);
    }
}
