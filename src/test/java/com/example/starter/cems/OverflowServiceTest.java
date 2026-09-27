package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.OverflowTypes.AdjustmentResult;
import com.example.starter.cems.OverflowTypes.CorrectRainfallRequest;
import com.example.starter.cems.OverflowTypes.CreatePermitRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementView;
import com.example.starter.cems.OverflowTypes.DualAverageResult;
import com.example.starter.cems.OverflowTypes.FreezeResult;
import com.example.starter.cems.OverflowTypes.HourDataRequest;
import com.example.starter.cems.OverflowTypes.HourView;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallAdjustmentRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.UpdatePermitRequest;

/**
 * 雨季溢流证据标记与双口径统计业务测试（B 题，真实 H2 MODE=MySQL）：
 * 许可版本/乐观锁、整点半开窗口、恰等雨量合法、缺雨量待核、复核冻结闩锁、冻结前后雨量路径、
 * 连续段断段（缺点/跨版本）与专项异常、双口径分母与缺点、空集合无结果、日结快照、幂等指纹与失败不占键、
 * 以及溢流不影响旧超标链/限排令。
 */
@SpringBootTest
class OverflowServiceTest {

    private static final String CODE = "OUT-OV";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final LocalDateTime WIN_START = LocalDateTime.of(2026, 9, 26, 0, 0);
    private static final LocalDateTime WIN_END = LocalDateTime.of(2026, 9, 27, 0, 0);
    private static final LocalDateTime FREEZE_AT = LocalDateTime.of(2026, 9, 26, 12, 0);

    @Autowired
    private OverflowService overflow;
    @Autowired
    private CemsService cems;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.resetUtc();
        clock.setFixed(CemsTestSupport.T0);
        clock.setFixedUtc(LocalDateTime.of(2026, 9, 26, 12, 0));
        CemsTestSupport.register(cems, CODE, "50", "10");
    }

    private PermitView permit(int maxHours) {
        return overflow.createPermit(new CreatePermitRequest(
                "permit-" + maxHours + "-" + System.nanoTime(), CODE,
                WIN_START, WIN_END, new BigDecimal("10.00"), maxHours));
    }

    private HourView hour(int h, String discharge, String rainfall, boolean observed, String requestId) {
        return overflow.reportHour(new HourDataRequest(requestId, CODE,
                LocalDateTime.of(2026, 9, 26, h, 0),
                new BigDecimal(discharge), rainfall == null ? null : new BigDecimal(rainfall), observed));
    }

    private long anomalyCount() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_anomaly WHERE outlet_code = ?", Long.class, CODE);
        return c == null ? 0 : c;
    }

    // ---------------- 许可版本 ----------------

    @Test
    void permitStartsAtVersionOneAndDuplicateCreateConflicts() {
        PermitView v1 = permit(3);
        assertEquals(1, v1.version());
        assertEquals("ACTIVE", v1.status());
        assertThrows(ConflictException.class, () -> overflow.createPermit(new CreatePermitRequest(
                "permit-dup", CODE, WIN_START, WIN_END, new BigDecimal("8"), 3)));
    }

    @Test
    void permitCreateValidatesWindowAndOutlet() {
        assertThrows(IllegalArgumentException.class, () -> overflow.createPermit(new CreatePermitRequest(
                "p-bad-window", CODE, WIN_END, WIN_START, new BigDecimal("10"), 3)));
        assertThrows(IllegalArgumentException.class, () -> overflow.createPermit(new CreatePermitRequest(
                "p-bad-hour", CODE, WIN_START, LocalDateTime.of(2026, 9, 26, 0, 30),
                new BigDecimal("10"), 3)));
        assertThrows(NotFoundException.class, () -> overflow.createPermit(new CreatePermitRequest(
                "p-missing", "NO-OUTLET", WIN_START, WIN_END, new BigDecimal("10"), 3)));
    }

    @Test
    void updatePermitUsesExpectedVersionAndIncrementsKeepingOldVersionEvidence() {
        permit(2);
        PermitView v2 = overflow.updatePermit(new UpdatePermitRequest(
                "permit-v2", CODE, 1, WIN_START, WIN_END.plusDays(1), new BigDecimal("12"), 4));
        assertEquals(2, v2.version());
        assertEquals("ACTIVE", v2.status());
        assertEquals(0, new BigDecimal("12.00").compareTo(v2.minHourlyRainfall()));
        assertEquals(4, v2.maxConsecutiveHours());

        List<PermitView> versions = overflow.listPermits(CODE);
        assertEquals(2, versions.size());
        assertEquals("SUPERSEDED", versions.get(0).status(), "旧版本必须永久留证");
        assertEquals("ACTIVE", versions.get(1).status());

        // 版本落后：409，不产生新版本。
        assertThrows(ConflictException.class, () -> overflow.updatePermit(new UpdatePermitRequest(
                "permit-stale", CODE, 1, WIN_START, WIN_END, new BigDecimal("9"), 2)));
        assertEquals(2, overflow.listPermits(CODE).size());
        assertThrows(IllegalArgumentException.class, () -> overflow.updatePermit(new UpdatePermitRequest(
                "permit-null-ver", CODE, null, WIN_START, WIN_END, new BigDecimal("9"), 2)));
    }

    // ---------------- 小时标记判定 ----------------

    @Test
    void hourMarkingRulesNormalPendingCompliantDenied() {
        permit(3);
        // 未观测溢流：NORMAL。
        assertEquals("NORMAL", hour(1, "5", "0", false, "h-normal").overflowStatus());
        // 观测溢流但缺雨量：PENDING 待核，不当零、不豁免。
        HourView pending = hour(2, "8", null, true, "h-pending");
        assertEquals("PENDING", pending.overflowStatus());
        assertNull(pending.rainfallMm());
        assertNull(pending.evaluatedPermitVersion());
        // 雨量恰等于门槛 10.00 且完整落窗：COMPLIANT（恰等合法）。
        HourView equal = hour(3, "9", "10.00", true, "h-equal");
        assertEquals("COMPLIANT", equal.overflowStatus());
        assertEquals(1, equal.evaluatedPermitVersion());
        // 雨量低于门槛：DENIED。
        assertEquals("DENIED", hour(4, "9", "9.99", true, "h-lowrain").overflowStatus());
        // 雨量为 0 不能豁免溢流：DENIED（与缺雨量 PENDING 明确区分）。
        assertEquals("DENIED", hour(5, "9", "0", true, "h-zerorain").overflowStatus());
        // 非整点：400。
        assertThrows(IllegalArgumentException.class, () -> overflow.reportHour(new HourDataRequest(
                "h-bad", CODE, LocalDateTime.of(2026, 9, 26, 6, 30),
                new BigDecimal("9"), new BigDecimal("20"), true)));
    }

    @Test
    void hourNotFullyInsideWindowIsDeniedAndWindowBoundaryIsHalfOpen() {
        // 窗口 [26 日 00:00, 26 日 03:00)
        overflow.createPermit(new CreatePermitRequest("p-3h", CODE,
                WIN_START, LocalDateTime.of(2026, 9, 26, 3, 0), new BigDecimal("10"), 3));
        // 02:00 小时 [02,03) 完整落窗：COMPLIANT。
        assertEquals("COMPLIANT", hour(2, "9", "11", true, "h-in").overflowStatus());
        // 03:00 已在半开终点之外：DENIED。
        assertEquals("DENIED", hour(3, "9", "11", true, "h-edge").overflowStatus());
    }

    @Test
    void observedOverflowWithoutActivePermitIsDenied() {
        // 不建许可直接上报溢流。
        HourView denied = hour(7, "9", "50", true, "h-nopermit");
        assertEquals("DENIED", denied.overflowStatus());
        assertNull(denied.evaluatedPermitVersion());
    }

    @Test
    void duplicateHourSameContentReplaysDifferentContentConflicts() {
        permit(3);
        HourView first = hour(8, "9", "11", true, "h-first");
        HourView again = hour(8, "9", "11", true, "h-again-key");
        assertTrue(again.deduplicated());
        assertEquals(first.overflowStatus(), again.overflowStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_hour_record WHERE outlet_code = ?", Long.class, CODE));
        assertThrows(ConflictException.class, () -> hour(8, "9.5", "11", true, "h-diff"));
    }

    // ---------------- 雨量更正与复核冻结 ----------------

    @Test
    void correctingRainfallBeforeFreezeRecomputesMarkAndBumpsRainfallVersion() {
        permit(3);
        HourView pending = hour(2, "8", null, true, "hr-pending");
        assertEquals(1, pending.rainfallVersion());
        assertEquals("PENDING", pending.overflowStatus());

        RainfallCorrectionResult corrected = overflow.correctRainfall(new CorrectRainfallRequest(
                "rain-fix", CODE, pending.hourUtc(), new BigDecimal("10.00")));
        assertEquals(2, corrected.newRainfallVersion());
        assertEquals("COMPLIANT", corrected.overflowStatusAfter());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM overflow_hour_record WHERE outlet_code = ? AND hour_utc = ?",
                CODE, java.sql.Timestamp.valueOf(pending.hourUtc()));
        assertEquals(2, ((Number) row.get("rainfall_version")).intValue());
        assertEquals("COMPLIANT", row.get("overflow_status"));

        // 再把雨量改到门槛以下：重算应变 DENIED，版本继续 +1。
        RainfallCorrectionResult down = overflow.correctRainfall(new CorrectRainfallRequest(
                "rain-fix-2", CODE, pending.hourUtc(), new BigDecimal("5")));
        assertEquals(3, down.newRainfallVersion());
        assertEquals("DENIED", down.overflowStatusAfter());
    }

    @Test
    void reviewFreezeLocksVersionsAndReplaysConsistentlyAndRejectsDifferentParams() {
        permit(3);
        HourView compliant = hour(2, "8", "12", true, "hf-comp");

        FreezeResult frozen = overflow.reviewFreeze(new ReviewFreezeRequest(
                "freeze-1", CODE, compliant.hourUtc(), FREEZE_AT));
        assertEquals("COMPLIANT", frozen.overflowStatus());
        assertEquals(1, frozen.permitVersionFrozen());
        assertEquals(1, frozen.rainfallVersionFrozen());
        assertEquals(FREEZE_AT, frozen.frozenAtUtc());

        // 同键同参重放首次冻结结果。
        FreezeResult replay = overflow.reviewFreeze(new ReviewFreezeRequest(
                "freeze-1", CODE, compliant.hourUtc(), FREEZE_AT));
        assertEquals(frozen.permitVersionFrozen(), replay.permitVersionFrozen());
        assertEquals(frozen.frozenAtUtc(), replay.frozenAtUtc());

        // 同键异参（不同冻结时刻）：409。
        assertThrows(ConflictException.class, () -> overflow.reviewFreeze(new ReviewFreezeRequest(
                "freeze-1", CODE, compliant.hourUtc(), FREEZE_AT.plusMinutes(1))));

        // 冻结后更正雨量：422 业务拒绝。
        assertThrows(BusinessRejectException.class, () -> overflow.correctRainfall(new CorrectRainfallRequest(
                "rain-after-freeze", CODE, compliant.hourUtc(), new BigDecimal("99"))));
    }

    @Test
    void freezeRejectsNormalAndPendingHours() {
        permit(3);
        HourView normal = hour(1, "5", "0", false, "fz-normal");
        HourView pending = hour(2, "8", null, true, "fz-pending");
        BusinessRejectException rejectNormal = assertThrows(BusinessRejectException.class,
                () -> overflow.reviewFreeze(new ReviewFreezeRequest(
                        "fz-n", CODE, normal.hourUtc(), FREEZE_AT)));
        assertTrue(rejectNormal.getMessage().contains("无需复核冻结"));
        BusinessRejectException rejectPending = assertThrows(BusinessRejectException.class,
                () -> overflow.reviewFreeze(new ReviewFreezeRequest(
                        "fz-p", CODE, pending.hourUtc(), FREEZE_AT)));
        assertTrue(rejectPending.getMessage().contains("待核"));
        assertNull(jdbc.queryForMap("SELECT frozen_at AS f FROM overflow_hour_record WHERE outlet_code = ? "
                + "AND hour_utc = ?", CODE, java.sql.Timestamp.valueOf(pending.hourUtc())).get("f"));
    }

    @Test
    void independentAdjustmentAfterFreezeLeavesEvidenceAndSnapshotsUntouched() {
        permit(3);
        HourView compliant = hour(2, "8", "12", true, "adj-comp");
        overflow.reviewFreeze(new ReviewFreezeRequest(
                "adj-freeze", CODE, compliant.hourUtc(), FREEZE_AT));

        // 先做日结快照。
        overflow.reportHour(new HourDataRequest("adj-n", CODE,
                LocalDateTime.of(2026, 9, 26, 5, 0), new BigDecimal("4"), new BigDecimal("0"), false));
        DailySettlementView before = overflow.settleDaily(new DailySettlementRequest("settle-adj", CODE, DAY));

        AdjustmentResult adjustment = overflow.independentAdjustment(new RainfallAdjustmentRequest(
                "adj-1", CODE, compliant.hourUtc(), new BigDecimal("3.5"), "雨后人工核减"));
        assertEquals(1, adjustment.frozenRainfallVersion());
        assertEquals(0, new BigDecimal("12").compareTo(adjustment.oldRainfallMm()));
        assertEquals(0, new BigDecimal("3.5").compareTo(adjustment.newRainfallMm()));

        // 独立调差不回写小时记录。
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM overflow_hour_record WHERE outlet_code = ? AND hour_utc = ?",
                CODE, java.sql.Timestamp.valueOf(compliant.hourUtc()));
        assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) row.get("rainfall_mm")));
        assertEquals(1, ((Number) row.get("rainfall_version")).intValue(), "冻结后雨量版本不得改变");
        assertEquals("COMPLIANT", row.get("overflow_status"));
        // 独立调差不改日结快照。
        DailySettlementView after = overflow.settleDaily(new DailySettlementRequest("settle-adj", CODE, DAY));
        assertTrue(after.replayed());
        assertEquals(before.allAvg(), after.allAvg());
        assertEquals(before.normalAvg(), after.normalAvg());

        // 未冻结小时不能调差：422。
        HourView other = hour(6, "4", "0", false, "adj-other");
        assertThrows(BusinessRejectException.class, () -> overflow.independentAdjustment(
                new RainfallAdjustmentRequest("adj-2", CODE, other.hourUtc(),
                        new BigDecimal("1"), "x")));
    }

    // ---------------- 连续段断段与专项异常 ----------------

    @Test
    void consecutiveCompliantSegmentBeyondLimitRaisesAnomaly() {
        permit(2);
        hour(8, "9", "11", true, "s-8");
        hour(9, "9", "11", true, "s-9");
        assertEquals(0, anomalyCount(), "恰为最大连续 2 小时，不异常");
        hour(10, "9", "11", true, "s-10");
        assertEquals(1L, anomalyCount(), "连续 3 小时严格大于最大 2 小时，专项异常");
        Map<String, Object> anomaly = jdbc.queryForMap(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ?", CODE);
        assertEquals(3, ((Number) anomaly.get("segment_hours")).intValue());
        assertEquals(1, ((Number) anomaly.get("permit_version")).intValue());
        assertEquals(java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 8, 0)),
                anomaly.get("segment_start_utc"));
        assertEquals(java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 11, 0)),
                anomaly.get("segment_end_utc"));
    }

    @Test
    void missingHourBreaksSegmentAndCorrectingRainfallCanClearAnomaly() {
        permit(2);
        hour(8, "9", "11", true, "g-8");
        hour(9, "9", "11", true, "g-9");
        // 缺 10 点；11 点与 09 点不相邻，段断开，不构成 3 连段。
        hour(11, "9", "11", true, "g-11");
        assertEquals(0, anomalyCount(), "缺点必须断段");

        // 08/09/10 三连段触发异常后，把中间 09 点雨量更正到门槛以下，异常应随重算清除。
        overflow.correctRainfall(new CorrectRainfallRequest(
                "g-fix", CODE, LocalDateTime.of(2026, 9, 26, 9, 0), new BigDecimal("1")));
        assertEquals(0, anomalyCount());
    }

    @Test
    void crossingPermitVersionBreaksSegmentAndNewSegmentScopedToNewVersion() {
        permit(2);
        hour(8, "9", "11", true, "x-8");
        hour(9, "9", "11", true, "x-9");
        hour(10, "9", "11", true, "x-10");
        assertEquals(1L, anomalyCount(), "v1 下 08-10 三段超长");

        // 许可升级 v2：旧 COMPLIANT 点仍引用 v1，不回改。
        overflow.updatePermit(new UpdatePermitRequest(
                "x-v2", CODE, 1, WIN_START, WIN_END, new BigDecimal("10"), 2));
        // 11 点按 v2 评价：与 10 点（v1）相邻但跨许可版本，必须断段。
        HourView h11 = hour(11, "9", "11", true, "x-11");
        assertEquals(2, h11.evaluatedPermitVersion());
        // 仍是 v1 那一条异常，没有把 v1/v2 混成 4 连段。
        assertEquals(1L, anomalyCount());
        Map<String, Object> anomaly = jdbc.queryForMap(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ?", CODE);
        assertEquals(1, ((Number) anomaly.get("permit_version")).intValue());

        // v2 下再连续到 3 连（11/12/13）：按 v2 新起一条超长异常。
        hour(12, "9", "11", true, "x-12");
        hour(13, "9", "11", true, "x-13");
        assertEquals(2L, anomalyCount());
        List<Map<String, Object>> anomalies = jdbc.queryForList(
                "SELECT * FROM overflow_anomaly WHERE outlet_code = ? ORDER BY id", CODE);
        assertEquals(2, ((Number) anomalies.get(1).get("permit_version")).intValue());
        assertEquals(3, ((Number) anomalies.get(1).get("segment_hours")).intValue());
    }

    // ---------------- 双口径均值与日结快照 ----------------

    @Test
    void dualAverageExcludesOnlyFullyEvidencedCompliantHoursAndStatesDenominatorsAndGaps() {
        permit(3);
        hour(8, "10", "0", false, "d-8");     // NORMAL 10
        hour(9, "100", "11", true, "d-9");   // COMPLIANT 100
        hour(10, "20", null, true, "d-10");  // PENDING 20（缺雨量不排除、不豁免）
        // 缺 11 点
        hour(12, "40", "11", true, "d-12");  // COMPLIANT 40

        LocalDateTime from = LocalDateTime.of(2026, 9, 26, 8, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 26, 13, 0);
        DualAverageResult normal = overflow.queryDualAverage(CODE, from, to, "NORMAL");
        assertFalse(normal.noResult());
        assertEquals(0, new BigDecimal("42.5000").compareTo(normal.allAverage()),
                "全部均值不排除：(10+100+20+40)/4=42.5");
        assertEquals(0, new BigDecimal("15.0000").compareTo(normal.normalAverage()),
                "正常均值仅排除两个证据齐全的 COMPLIANT：(10+20)/2=15");
        assertEquals(4, normal.allDenominator());
        assertEquals(2, normal.normalDenominator());
        assertEquals(2, normal.excludedCompliantCount());
        assertEquals(1, normal.pendingCount(), "PENDING 不被排除也不被豁免");
        assertEquals(1, normal.missingPointCount());
        assertEquals(List.of(LocalDateTime.of(2026, 9, 26, 11, 0)), normal.missingHours());
        assertEquals(4, normal.present().size());

        DualAverageResult all = overflow.queryDualAverage(CODE, from, to, "ALL");
        assertEquals(normal.allAverage(), all.allAverage());
        assertThrows(IllegalArgumentException.class,
                () -> overflow.queryDualAverage(CODE, from, to, "BAD"));
    }

    @Test
    void dualAverageOnEmptyWindowReturnsNoResultInsteadOfZero() {
        permit(3);
        DualAverageResult empty = overflow.queryDualAverage(
                CODE, LocalDateTime.of(2026, 9, 26, 8, 0),
                LocalDateTime.of(2026, 9, 26, 10, 0), "NORMAL");
        assertTrue(empty.noResult());
        assertNull(empty.normalAverage());
        assertNull(empty.allAverage());
        assertEquals(0, empty.allDenominator());
        assertEquals(2, empty.missingPointCount());
    }

    @Test
    void dailySettlementSnapshotsBothBasisAndReplaysSameResult() {
        permit(3);
        hour(8, "10", "0", false, "ds-8");
        hour(9, "100", "11", true, "ds-9");
        hour(10, "20", null, true, "ds-10");

        DailySettlementView first = overflow.settleDaily(new DailySettlementRequest("ds-1", CODE, DAY));
        assertFalse(first.replayed());
        assertEquals(24, first.requestedHours());
        assertEquals(3, first.presentCount());
        assertEquals(1, first.excludedCount());
        assertEquals(1, first.pendingCount());
        assertEquals(21, first.missingPointCount(), "缺点须明示：24-3");
        assertEquals(3, first.allDenominator());
        assertEquals(2, first.normalDenominator());
        assertEquals(0, new BigDecimal("43.3333").compareTo(first.allAvg()));
        assertEquals(0, new BigDecimal("15.0000").compareTo(first.normalAvg()));

        DailySettlementView replay = overflow.settleDaily(new DailySettlementRequest("ds-1", CODE, DAY));
        assertTrue(replay.replayed());
        assertEquals(first.allAvg(), replay.allAvg());
        assertEquals(first.normalAvg(), replay.normalAvg());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_settlement WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void dailySettlementOnEmptyDayReturnsNoResultAndPersistsNothing() {
        permit(3);
        DailySettlementView empty = overflow.settleDaily(
                new DailySettlementRequest("ds-empty", CODE, DAY));
        assertNull(empty.allAvg());
        assertNull(empty.normalAvg());
        assertEquals(24, empty.missingPointCount());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_settlement WHERE outlet_code = ?", Long.class, CODE));
    }

    // ---------------- 幂等 ----------------

    @Test
    void failedRequestDoesNotOccupyKeyAndFingerprintDistinguishesDelimiterText() {
        permit(3);
        // 首次用该 requestId 调非法参数（400），失败不占键；随后同键合法请求必须成功。
        assertThrows(IllegalArgumentException.class, () -> overflow.reportHour(new HourDataRequest(
                "reuse-key", CODE, LocalDateTime.of(2026, 9, 26, 8, 0),
                null, new BigDecimal("11"), true)));
        HourView ok = hour(8, "9", "11", true, "reuse-key");
        assertEquals("COMPLIANT", ok.overflowStatus());

        // 结构化指纹：含分隔符/JSON 语法字符的文本也必须与其他字段组合区分。
        String tricky = "a|,b{c}=\"x\"";
        Map<String, Object> f1 = Map.of("reason", tricky, "v", 1);
        Map<String, Object> f2 = Map.of("reason", "a", "v", "1|,b{c}=\"x\"");
        String fp1 = new IdempotencySupport(
                new com.fasterxml.jackson.databind.ObjectMapper())
                .fingerprintStructured("rid", "T", f1);
        String fp2 = new IdempotencySupport(
                new com.fasterxml.jackson.databind.ObjectMapper())
                .fingerprintStructured("rid", "T", f2);
        assertNotEquals(fp1, fp2, "合法文本含分隔符也须区分");
    }

    @Test
    void sameKeyDifferentParamsConflictAndReplaysFirstResult() {
        permit(3);
        HourView first = hour(8, "9", "11", true, "idem-hour");
        assertThrows(ConflictException.class, () -> hour(8, "9", "12", true, "idem-hour"));
        HourView replay = hour(8, "9", "11", true, "idem-hour");
        assertEquals(first.rainfallMm(), replay.rainfallMm());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_hour_record WHERE outlet_code = ?", Long.class, CODE));
    }

    // ---------------- 旧流程保留：溢流不影响原超标链/限排令 ----------------

    @Test
    void overflowDoesNotCancelRestrictionOrderOrRelieveExceedanceChain() {
        permit(2);
        // 旧域：连续 3 次小时超标触发限排令。
        cems.reportHourly(new HourlyReadingRequest(CODE, LocalDateTime.of(2026, 9, 26, 8, 0),
                new BigDecimal("11")));
        cems.reportHourly(new HourlyReadingRequest(CODE, LocalDateTime.of(2026, 9, 26, 9, 0),
                new BigDecimal("12")));
        var third = cems.reportHourly(new HourlyReadingRequest(CODE, LocalDateTime.of(2026, 9, 26, 10, 0),
                new BigDecimal("13")));
        assertTrue(third.restrictionTriggered());

        // 溢流域：合规溢流证据齐全并冻结。
        hour(8, "100", "11", true, "legacy-8");
        hour(9, "100", "11", true, "legacy-9");
        HourView comp = hour(10, "100", "11", true, "legacy-10");
        overflow.reviewFreeze(new ReviewFreezeRequest(
                "legacy-freeze", CODE, comp.hourUtc(), FREEZE_AT));

        // 限排令仍待确认，原小时超标链 3 个 ACTIVE 标记不变，排口仍 RESTRICTED。
        assertEquals("PENDING_CONFIRM", cems.getOrder(third.orderNo()).status());
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exceedance_mark WHERE outlet_code = ? AND mark_type='HOURLY_AVG' "
                        + "AND status='ACTIVE'", Long.class, CODE));
        assertEquals("RESTRICTED", cems.getOutlet(CODE).status());

        // 旧无溢流数据的排口行为不变：另一个未建溢流许可的排口旧流程正常。
        CemsTestSupport.register(cems, "OUT-PLAIN", "50", "10");
        var plain = cems.reportHourly(new HourlyReadingRequest("OUT-PLAIN",
                LocalDateTime.of(2026, 9, 26, 8, 0), new BigDecimal("11")));
        assertEquals(1, plain.chainIndex());
    }

    @Test
    void missingOrderReturnsNotFoundKeepingOldSemantics() {
        assertThrows(NotFoundException.class, () -> cems.getOrder("RO-NONE"));
    }
}
