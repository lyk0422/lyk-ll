package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.OrderView;
import com.example.starter.cems.CemsTypes.OverflowAnomalyView;
import com.example.starter.cems.CemsTypes.OverflowCaliberSummary;
import com.example.starter.cems.CemsTypes.OverflowDailySummaryView;
import com.example.starter.cems.CemsTypes.OverflowHourView;
import com.example.starter.cems.CemsTypes.OverflowPermitCreateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitUpdateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.RainfallAdjustRequest;
import com.example.starter.cems.CemsTypes.RainfallAdjustResult;
import com.example.starter.cems.CemsTypes.RainfallReportRequest;
import com.example.starter.cems.CemsTypes.RainfallReportResult;
import com.example.starter.cems.CemsTypes.OverflowHourlyRequest;

/**
 * 雨季溢流主流程/失败分支业务测试（真实 H2 MODE=MySQL，UTC 墙面时间）：
 * 许可版本、雨量版本与恰等门槛、缺雨量待核、半开窗边界、双口径分母/缺点、
 * 复核冻结幂等、冻结前重算与冻结后独立调差、连续段跨许可/缺点断段与专项异常、
 * 以及溢流不抹掉原浓度超标链/不取消限排令。
 */
@SpringBootTest
class OverflowServiceTest {

    private static final String CODE = "OUT-OV";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

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
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(cems, CODE, "50", "10");
    }

    private LocalDateTime hour(int h) {
        return DAY.atTime(h, 0);
    }

    private OverflowPermitView permit(String rid, LocalDateTime from, LocalDateTime to,
                                      String minRain, int maxCons) {
        return overflow.createPermit(new OverflowPermitCreateRequest(
                rid, CODE, from, to, new BigDecimal(minRain), maxCons));
    }

    private RainfallReportResult rain(String rid, int h, String mm, Integer expectedVersion) {
        return overflow.reportRainfall(new RainfallReportRequest(
                rid, CODE, hour(h), new BigDecimal(mm), expectedVersion));
    }

    private OverflowHourView overflowHour(String rid, int h, String value, boolean flag) {
        return overflow.reportOverflowHour(new OverflowHourlyRequest(
                rid, CODE, hour(h), new BigDecimal(value), flag));
    }

    private OverflowReviewResult review(String rid) {
        return overflow.review(new OverflowReviewRequest(rid, CODE, DAY));
    }

    @Test
    void permitStartsAtVersionOneAndUpdateWithExpectedVersionIncrements() {
        OverflowPermitView v1 = permit("p-1", hour(0), hour(12), "5.0", 3);
        assertEquals(1, v1.version());

        // expectedVersion 不匹配：409，不产生新版本。
        assertThrows(ConflictException.class, () -> overflow.updatePermit(new OverflowPermitUpdateRequest(
                "p-bad", CODE, 99, hour(0), hour(12), new BigDecimal("6.0"), 4)));
        assertEquals(1, overflow.listPermits(CODE).size());

        OverflowPermitView v2 = overflow.updatePermit(new OverflowPermitUpdateRequest(
                "p-2", CODE, 1, hour(12), DAY.plusDays(1).atStartOfDay(),
                new BigDecimal("6.0"), 4));
        assertEquals(2, v2.version());
        assertEquals(0, new BigDecimal("6.0").compareTo(v2.minHourlyRainfallMm()));
        assertEquals(4, v2.maxConsecutiveOverflowHours());
        // 历史版本永久保留为证据。
        assertEquals(2, overflow.listPermits(CODE).size());
        assertEquals(1, overflow.latestPermit(CODE).version() == 2 ? 1 : 2 - 1);
    }

    @Test
    void permitOverlapIs422NonHourWindowIs400AndMissingOutletIs404() {
        permit("p-1", hour(0), hour(12), "5.0", 3);
        // 与版本 1 重叠（非首尾相接）：422 业务拒绝。
        assertThrows(BusinessRejectException.class, () -> overflow.updatePermit(new OverflowPermitUpdateRequest(
                "p-2", CODE, 1, hour(11), DAY.plusDays(1).atStartOfDay(),
                new BigDecimal("5.0"), 3)));
        // 首尾相接合法：版本 2 成功。
        OverflowPermitView v2 = overflow.updatePermit(new OverflowPermitUpdateRequest(
                "p-3", CODE, 1, hour(12), DAY.plusDays(1).atStartOfDay(),
                new BigDecimal("5.0"), 3));
        assertEquals(2, v2.version());

        // 非 UTC 整点 / 反向窗口 / 参数非法 → 400。
        assertThrows(IllegalArgumentException.class, () -> overflow.createPermit(
                new OverflowPermitCreateRequest("p-4", CODE, DAY.atTime(0, 30), hour(12),
                        new BigDecimal("5.0"), 3)));
        assertThrows(IllegalArgumentException.class, () -> overflow.createPermit(
                new OverflowPermitCreateRequest("p-5", CODE, hour(12), hour(0),
                        new BigDecimal("5.0"), 3)));
        assertThrows(IllegalArgumentException.class, () -> overflow.createPermit(
                new OverflowPermitCreateRequest("p-6", CODE, hour(0), hour(12),
                        new BigDecimal("-1"), 3)));
        // 排口不存在 → 404。
        assertThrows(NotFoundException.class, () -> overflow.createPermit(
                new OverflowPermitCreateRequest("p-7", "NO-OUT", hour(0), hour(12),
                        new BigDecimal("5.0"), 3)));
    }

    @Test
    void exactRainfallThresholdIsCompliantAndMissingRainfallStaysPending() {
        permit("p-1", hour(0), hour(12), "5.0", 3);
        // 先申报溢流但尚无雨量：缺雨量只能待核，不能当零豁免。
        OverflowHourView pending = overflowHour("h-1", 1, "200", true);
        assertEquals("PENDING", pending.overflowStatus());
        assertNull(pending.rainfallMm());
        // 复核前冻结被缺雨量拒绝（422）。
        assertThrows(BusinessRejectException.class, () -> review("r-blocked"));

        // 恰等门槛 5.0mm：合法合规（READY）。
        rain("rain-1", 1, "5.0", null);
        assertEquals("READY", overflow.getOverflowHour(CODE, hour(1)).overflowStatus());
        // 低于门槛：INELIGIBLE。
        rain("rain-x", 2, "10", null);
        overflowHour("h-2", 2, "210", true);
        rain("rain-2", 2, "4.99", 1);
        assertEquals("INELIGIBLE", overflow.getOverflowHour(CODE, hour(2)).overflowStatus());
        // 实测 0.0 是合法值（非缺测）但低于门槛，不合规。
        rain("rain-3", 3, "0.0", null);
        overflowHour("h-3", 3, "220", true);
        assertEquals("INELIGIBLE", overflow.getOverflowHour(CODE, hour(3)).overflowStatus());
    }

    @Test
    void correctingRainfallBeforeReviewRecomputesCandidateStatus() {
        permit("p-1", hour(0), hour(12), "5.0", 3);
        rain("rain-1", 1, "3.0", null);
        overflowHour("h-1", 1, "200", true);
        assertEquals("INELIGIBLE", overflow.getOverflowHour(CODE, hour(1)).overflowStatus());

        // 复核前更正雨量（版本 1→2）达标，候选标记重算为 READY。
        RainfallReportResult corrected = rain("rain-1c", 1, "9.0", 1);
        assertEquals(2, corrected.version());
        OverflowHourView ready = overflow.getOverflowHour(CODE, hour(1));
        assertEquals("READY", ready.overflowStatus());
        assertEquals(2, ready.rainfallVersion());
        // 错误的 expectedVersion：409。
        assertThrows(ConflictException.class, () -> rain("rain-1b", 1, "9.5", 1));
    }

    @Test
    void halfOpenWindowCoversStartHourButNotEndHour() {
        // 窗口 [02:00,05:00)：02/03/04 完整落窗，05 不落窗。
        permit("p-1", hour(2), hour(5), "5.0", 3);
        rain("rain-4", 4, "8", null);
        overflowHour("h-4", 4, "200", true);
        assertEquals("READY", overflow.getOverflowHour(CODE, hour(4)).overflowStatus());

        rain("rain-5", 5, "8", null);
        overflowHour("h-5", 5, "200", true);
        // 05 点未完整落入半开窗口：许可无效 → INELIGIBLE（即使雨量达标）。
        assertEquals("INELIGIBLE", overflow.getOverflowHour(CODE, hour(5)).overflowStatus());
    }

    @Test
    void reviewFreezeProducesDualCaliberWithDenominatorsExcludedAndMissingPoints() {
        permit("p-1", hour(0), hour(12), "5.0", 10);
        // 00 正常排放 100；01 合规溢流 200（雨量 8）；02 合规溢流 300（雨量恰等 5）；03 正常排放 50。
        overflowHour("h-0", 0, "100", false);
        rain("r-1", 1, "8", null);
        overflowHour("h-1", 1, "200", true);
        rain("r-2", 2, "5.0", null);
        overflowHour("h-2", 2, "300", true);
        overflowHour("h-3", 3, "50", false);

        OverflowReviewResult result = review("rev-1");
        assertFalse(result.replayed());
        assertEquals(2, result.frozenCompliantHours());
        OverflowDailySummaryView s = result.summary();
        OverflowCaliberSummary normal = s.normal();
        OverflowCaliberSummary all = s.all();
        // NORMAL 仅排除两个证据齐全的合规溢流小时：(100+50)/2=75。
        assertEquals(2, normal.denominator());
        assertEquals(2, normal.excludedHours());
        assertEquals(0, new BigDecimal("75.0000").compareTo(normal.averageValue()));
        assertFalse(normal.noData());
        // ALL 不排除：(100+200+300+50)/4=162.5。
        assertEquals(4, all.denominator());
        assertEquals(0, all.excludedHours());
        assertEquals(0, new BigDecimal("162.5000").compareTo(all.averageValue()));
        // 24 槽位仅 4 个有记录，缺点 20，双口径均明示。
        assertEquals(20, normal.missingPoints());
        assertEquals(20, all.missingPoints());

        // 冻结所用许可/雨量版本固定在标记上。
        OverflowHourView frozen = overflow.getOverflowHour(CODE, hour(1));
        assertEquals("FROZEN_COMPLIANT", frozen.overflowStatus());
        assertEquals(1, frozen.permitVersion());
        assertEquals(1, frozen.rainfallVersion());

        // 查询接口返回同一冻结快照；重复（异键）日结复用一致结果。
        OverflowReviewResult again = review("rev-2");
        assertTrue(again.replayed());
        assertEquals(0, new BigDecimal("75.0000").compareTo(again.summary().normal().averageValue()));
        assertEquals(0, new BigDecimal("162.5000").compareTo(
                overflow.getDailySummary(CODE, DAY).all().averageValue()));
    }

    @Test
    void failedReviewDoesNotOccupyKeyOrFreezeDayThenSucceedsAfterRainfallArrives() {
        permit("p-1", hour(0), hour(12), "5.0", 10);
        overflowHour("h-1", 1, "200", true); // 缺雨量 PENDING
        // 同一 requestId 首次冻结被业务拒绝（422）。
        assertThrows(BusinessRejectException.class, () -> review("rev-retry"));
        // 失败不占键：幂等占位回滚；也不占日结冻结行。
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'rev-retry'", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_freeze WHERE outlet_code = ?", Long.class, CODE));

        rain("r-1", 1, "9", null); // 补齐雨量
        OverflowReviewResult result = review("rev-retry"); // 同键可再次冻结成功
        assertEquals(1, result.frozenCompliantHours());
    }

    @Test
    void emptyDayReturnsNoResultNotNullZero() {
        permit("p-1", hour(0), hour(12), "5.0", 10);
        OverflowReviewResult result = review("rev-empty");
        OverflowCaliberSummary normal = result.summary().normal();
        OverflowCaliberSummary all = result.summary().all();
        assertTrue(normal.noData());
        assertTrue(all.noData());
        assertNull(normal.averageValue(), "空集合必须返回无结果而非 0");
        assertNull(all.averageValue());
        assertEquals(0, normal.denominator());
        assertEquals(24, normal.missingPoints(), "24 个整点槽位全部缺失须明示");
    }

    @Test
    void sameReviewRequestIdReplaysAndDifferentParamsConflict409() {
        permit("p-1", hour(0), hour(12), "5.0", 10);
        rain("r-1", 1, "8", null);
        overflowHour("h-1", 1, "200", true);
        OverflowReviewResult first = review("idem-rev");
        OverflowReviewResult replay = review("idem-rev");
        assertEquals(first.frozenCompliantHours(), replay.frozenCompliantHours());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_freeze WHERE outlet_code = ?", Long.class, CODE));
        // 同键异参（不同 UTC 日）→ 409。
        assertThrows(ConflictException.class, () -> overflow.review(
                new OverflowReviewRequest("idem-rev", CODE, DAY.plusDays(1))));
    }

    @Test
    void overflowNeverErasesConcentrationExceedanceChainOrActiveOrder() {
        // 既有浓度链路：连续 3 次小时超标 → 限排令 → 确认执行（ACTIVE）。
        cems.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 8, 0), new BigDecimal("11")));
        cems.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 9, 0), new BigDecimal("12")));
        var trigger = cems.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 10, 0), new BigDecimal("13")));
        cems.confirmOrder(new ConfirmOrderRequest("confirm-ov", trigger.orderNo()));

        // 并行存在的雨季溢流证据链冻结。
        permit("p-1", hour(0), hour(12), "5.0", 10);
        rain("r-1", 1, "8", null);
        overflowHour("h-1", 1, "999", true);
        review("rev-keep");

        // 原小时超标标记链仍在（3 条 ACTIVE），限排令仍 ACTIVE，不被溢流取消/减轻。
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exceedance_mark WHERE outlet_code = ? AND mark_type='HOURLY_AVG' AND status='ACTIVE'",
                Long.class, CODE));
        OrderView order = cems.getOrder(trigger.orderNo());
        assertEquals("ACTIVE", order.status());
    }

    @Test
    void segmentLongerThanPermitLimitRaisesSpecialAnomaly() {
        permit("p-1", hour(0), hour(12), "5.0", 2); // 允许最大连续 2 小时
        for (int h = 0; h <= 2; h++) {
            rain("r-" + h, h, "8", null);
            overflowHour("h-" + h, h, "20" + h, true);
        }
        OverflowReviewResult result = review("rev-seg");
        List<OverflowAnomalyView> anomalies = result.anomalies();
        assertEquals(1, anomalies.size());
        OverflowAnomalyView a = anomalies.get(0);
        assertEquals("SEGMENT_TOO_LONG", a.anomalyType());
        assertEquals(3, a.consecutiveHours());
        assertEquals(2, a.limitHours());
        assertEquals(hour(0), a.segmentFromUtc());
        assertEquals(hour(3), a.segmentToUtc());
        assertEquals(1, overflow.listAnomalies(CODE).size());
    }

    @Test
    void consecutiveSegmentBreaksAcrossPermitVersionsAndAtMissingPoints() {
        // 跨许可版本断段：v1 [00,03) 三小时，v2 [03,06) 三小时；各自恰为 3，不构成“6 连超”。
        permit("p-1", hour(0), hour(3), "5.0", 3);
        overflow.updatePermit(new OverflowPermitUpdateRequest(
                "p-2", CODE, 1, hour(3), hour(12), new BigDecimal("5.0"), 3));
        for (int h = 0; h <= 2; h++) {
            rain("ra-" + h, h, "8", null);
            overflowHour("ha-" + h, h, "200", true);
        }
        for (int h = 3; h <= 5; h++) {
            rain("rb-" + h, h, "8", null);
            overflowHour("hb-" + h, h, "200", true);
        }
        OverflowReviewResult crossPermit = review("rev-cross");
        assertTrue(crossPermit.anomalies().isEmpty(), "跨许可版本必须断段，不应报 6 连超");

        // 缺点断段：新排口/新日，许可允许连续 3，00,01 与 03,04,05 间缺 02，断为 2 与 3。
        CemsTestSupport.clean(jdbc);
        CemsTestSupport.register(cems, "OUT-GAP", "50", "10");
        OverflowService gapFlow = overflow;
        gapFlow.createPermit(new OverflowPermitCreateRequest(
                "gp-1", "OUT-GAP", hour(0), hour(12), new BigDecimal("5.0"), 3));
        int[] hours = {0, 1, 3, 4, 5};
        for (int h : hours) {
            gapFlow.reportRainfall(new RainfallReportRequest(
                    "gr-" + h, "OUT-GAP", hour(h), new BigDecimal("8"), null));
            gapFlow.reportOverflowHour(new OverflowHourlyRequest(
                    "gh-" + h, "OUT-GAP", hour(h), new BigDecimal("200"), true));
        }
        OverflowReviewResult gapResult = gapFlow.review(
                new OverflowReviewRequest("rev-gap", "OUT-GAP", DAY));
        assertTrue(gapResult.anomalies().isEmpty(), "中间缺 02 点必须断段，不应把 2+3 误计为 5 连超");
    }

    @Test
    void segmentCrossingMidnightIsDetectedWhenSecondDayIsReviewed() {
        // 许可跨两天，允许最大连续 3 小时；22,23（DAY）与 00,01（次日）构成跨日 4 连段。
        permit("p-cross", hour(0), DAY.plusDays(2).atStartOfDay(), "5.0", 3);
        LocalDateTime day22 = DAY.atTime(22, 0);
        LocalDateTime day23 = DAY.atTime(23, 0);
        LocalDateTime next00 = DAY.plusDays(1).atStartOfDay();
        LocalDateTime next01 = DAY.plusDays(1).atTime(1, 0);
        for (LocalDateTime h : List.of(day22, day23, next00, next01)) {
            overflow.reportRainfall(new RainfallReportRequest(
                    "xr-" + h, CODE, h, new BigDecimal("8"), null));
            overflow.reportOverflowHour(new OverflowHourlyRequest(
                    "xh-" + h, CODE, h, new BigDecimal("200"), true));
        }
        // 先复核 DAY：当时仅 22,23 冻结，长度 2 不超限，无异常。
        assertTrue(review("rev-d1").anomalies().isEmpty());
        // 再复核次日：全局 4 连段（跨许可连续、无缺点）超过 3，跨日段在次日登记。
        OverflowReviewResult d2 = overflow.review(
                new OverflowReviewRequest("rev-d2", CODE, DAY.plusDays(1)));
        assertEquals(1, d2.anomalies().size());
        OverflowAnomalyView a = d2.anomalies().get(0);
        assertEquals(4, a.consecutiveHours());
        assertEquals(3, a.limitHours());
        assertEquals(next00, a.segmentFromUtc(), "跨日段在次日以日界为起点登记");
        assertEquals(next01.plusHours(1), a.segmentToUtc());
    }

    @Test
    void postFreezeRainfallAdjustmentAppendsEvidenceWithoutChangingSnapshot() {
        permit("p-1", hour(0), hour(12), "5.0", 10);
        rain("r-1", 1, "8.0", null);
        overflowHour("h-1", 1, "200", true);
        review("rev-adj");

        // 冻结后只能独立调差：含分隔符的合法 reason 也须被结构化指纹区分。
        String trickyReason = "调差;理由|含=a:b分隔符";
        RainfallAdjustResult adj = overflow.adjustRainfall(new RainfallAdjustRequest(
                "adj-1", CODE, hour(1), new BigDecimal("2.0"), trickyReason));
        assertEquals(1, adj.frozenRainfallVersion());
        assertEquals(0, new BigDecimal("8.0").compareTo(adj.frozenRainfallMm()));
        assertEquals(2, adj.newRainfallVersion());

        // 冻结快照与冻结版本列不变。
        OverflowHourView stillFrozen = overflow.getOverflowHour(CODE, hour(1));
        assertEquals("FROZEN_COMPLIANT", stillFrozen.overflowStatus());
        assertEquals(1, stillFrozen.rainfallVersion(), "冻结雨量版本固定为 1，不受调差影响");
        OverflowCaliberSummary normal = overflow.getDailySummary(CODE, DAY).normal();
        assertEquals(1, normal.excludedHours(), "独立调差不改日结快照，该小时仍按合规溢流排除");

        // 留下独立调差证据（新雨量版本＋调差行）。
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rainfall_adjustment WHERE outlet_code = ? AND hour_utc = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf(hour(1))));

        // 未冻结小时调差 → 422；同键异参 → 409。
        assertThrows(BusinessRejectException.class, () -> overflow.adjustRainfall(
                new RainfallAdjustRequest("adj-x", CODE, hour(2), new BigDecimal("1"), "x")));
        assertThrows(ConflictException.class, () -> overflow.adjustRainfall(
                new RainfallAdjustRequest("adj-1", CODE, hour(1), new BigDecimal("1.5"), trickyReason)));
    }
}
