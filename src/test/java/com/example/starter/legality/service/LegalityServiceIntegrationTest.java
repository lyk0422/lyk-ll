package com.example.starter.legality.service;

import com.example.starter.legality.AbstractH2IntegrationTest;
import com.example.starter.legality.api.dto.CheckRequest;
import com.example.starter.legality.api.dto.CheckResponse;
import com.example.starter.legality.api.dto.DelayRequest;
import com.example.starter.legality.api.dto.DelayResponse;
import com.example.starter.legality.api.dto.MemberDutyRequest;
import com.example.starter.legality.api.dto.SealResponse;
import com.example.starter.legality.domain.ViolationReasons;
import com.example.starter.legality.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 值勤合规服务 H2 集成测试：FDP 三档边界、每日延长 2 小时、7 日 60 小时、
 * 休息期 10 小时、同键重放、整组一致、延误重算与封板终态。
 */
class LegalityServiceIntegrationTest extends AbstractH2IntegrationTest {

    private static final String FLIGHT_UTC = "UT001";

    @Autowired
    private LegalityService service;

    private MemberDutyRequest duty(String requestId, String member, String flightNo,
                                   String report, String end) {
        return new MemberDutyRequest(requestId, member, flightNo,
                Instant.parse(report), Instant.parse(end));
    }

    private CheckResponse checkOne(MemberDutyRequest d) {
        return service.check(new CheckRequest(List.of(d)));
    }

    // ------------------------------------------------------------------
    // FDP 三档上限边界：恰达上限合法，超出 1 分钟违规
    // ------------------------------------------------------------------

    @Test
    void fdpTier13_atLimitLegal_oneMinuteOverIllegal() {
        // 06:00-12:59 档：上限 13 小时（UTC 机场 UT001）。
        CheckResponse atLimit = checkOne(duty("r-13-ok", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T19:00:00Z"));
        assertEquals("LEGAL", atLimit.overallResult());
        assertEquals(780, atLimit.results().get(0).tierLimitMinutes());
        assertEquals(0, atLimit.results().get(0).reasons().size());

        CheckResponse over = checkOne(duty("r-13-no", "C002", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T19:01:00Z"));
        assertEquals("ILLEGAL", over.overallResult());
        assertEquals("ILLEGAL", over.results().get(0).result());
        assertFalse(over.results().get(0).accepted());
        assertTrue(over.results().get(0).reasons().contains(ViolationReasons.FDP_LIMIT_EXCEEDED));
    }

    @Test
    void fdpTier12_atLimitLegal_oneMinuteOverIllegal() {
        CheckResponse atLimit = checkOne(duty("r-12-ok", "C001", FLIGHT_UTC,
                "2026-09-20T13:00:00Z", "2026-09-21T01:00:00Z"));
        assertEquals("LEGAL", atLimit.overallResult());
        assertEquals(720, atLimit.results().get(0).tierLimitMinutes());

        CheckResponse over = checkOne(duty("r-12-no", "C002", FLIGHT_UTC,
                "2026-09-20T13:00:00Z", "2026-09-21T01:01:00Z"));
        assertEquals("ILLEGAL", over.overallResult());
        assertTrue(over.results().get(0).reasons().contains(ViolationReasons.FDP_LIMIT_EXCEEDED));
    }

    @Test
    void fdpTier10_atLimitLegal_oneMinuteOverIllegal() {
        CheckResponse atLimit = checkOne(duty("r-10-ok", "C001", FLIGHT_UTC,
                "2026-09-20T22:00:00Z", "2026-09-21T08:00:00Z"));
        assertEquals("LEGAL", atLimit.overallResult());
        assertEquals(600, atLimit.results().get(0).tierLimitMinutes());

        CheckResponse over = checkOne(duty("r-10-no", "C002", FLIGHT_UTC,
                "2026-09-20T22:00:00Z", "2026-09-21T08:01:00Z"));
        assertEquals("ILLEGAL", over.overallResult());
        assertTrue(over.results().get(0).reasons().contains(ViolationReasons.FDP_LIMIT_EXCEEDED));
    }

    // ------------------------------------------------------------------
    // 每日（UTC）累计延长 2 小时边界
    // ------------------------------------------------------------------

    @Test
    void dailyExtension_exactlyTwoHoursLegal_thenOneMoreMinuteRejected() {
        CheckResponse first = checkOne(duty("r-ext-base", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        assertEquals("LEGAL", first.overallResult());

        // 计划 FDP 10h + 延长 120 分钟 = 12h，未超 13h 档；当日延长恰满 2 小时，合法。
        DelayResponse accepted = service.reportDelay(new DelayRequest(
                "d-ext-120", "r-ext-base", 120, "天气延误"));
        assertFalse(accepted.late());
        assertTrue(accepted.accepted());
        assertEquals("LEGAL", accepted.result());
        assertEquals(120, accepted.totalExtensionMinutes());
        assertEquals(Instant.parse("2026-09-20T18:00:00Z"), accepted.dutyEnd());

        // 再多 1 分钟：当日 121 > 120，拒绝且不改变既有记录。
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.reportDelay(new DelayRequest("d-ext-121", "r-ext-base", 1, null)));
        assertTrue(ex.getMessage().contains(ViolationReasons.DAILY_EXTENSION_EXCEEDED));

        Integer delayCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delay_event WHERE duty_record_id = "
                        + "(SELECT id FROM duty_record WHERE request_id = 'r-ext-base')",
                Integer.class);
        assertEquals(1, delayCount);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_extension_minutes FROM duty_record WHERE request_id = 'r-ext-base'",
                Integer.class);
        assertEquals(120, total);
    }

    @Test
    void extensionBeyondFdpTierCapRejected() {
        // 计划 FDP 已达 12h（13h 档余量 60 分钟），延长 61 分钟触发 FDP 上限违规。
        checkOne(duty("r-cap-base", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T18:00:00Z"));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.reportDelay(new DelayRequest("d-cap-61", "r-cap-base", 61, null)));
        assertTrue(ex.getMessage().contains(ViolationReasons.FDP_LIMIT_EXCEEDED));
    }

    // ------------------------------------------------------------------
    // 连续 7 个 UTC 日历日 60 小时边界
    // ------------------------------------------------------------------

    @Test
    void weeklyDuty_exactlySixtyHoursLegal() {
        // 9/14-9/18 每天一段 10h 值勤（相邻间隔 14h，休息满足），累计 50h。
        for (int day = 14; day <= 18; day++) {
            CheckResponse r = checkOne(duty("r-w-ok-" + day, "C001", FLIGHT_UTC,
                    String.format("2026-09-%dT06:00:00Z", day),
                    String.format("2026-09-%dT16:00:00Z", day)));
            assertEquals("LEGAL", r.overallResult());
        }
        // 9/19 再值 10h：窗口 9/13-9/19 内合计恰 60 小时，合法。
        CheckResponse candidate = checkOne(duty("r-w-ok-cand", "C001", FLIGHT_UTC,
                "2026-09-19T06:00:00Z", "2026-09-19T16:00:00Z"));
        assertEquals("LEGAL", candidate.overallResult());
    }

    @Test
    void weeklyDuty_sixtyHoursPlusOneMinuteIllegal() {
        for (int day = 14; day <= 18; day++) {
            checkOne(duty("r-w-no-" + day, "C002", FLIGHT_UTC,
                    String.format("2026-09-%dT06:00:00Z", day),
                    String.format("2026-09-%dT16:00:00Z", day)));
        }
        // 候选 10 小时 1 分钟：窗口合计 60h01，超 60 小时；FDP、休息期本身均满足。
        CheckResponse candidate = checkOne(duty("r-w-no-cand", "C002", FLIGHT_UTC,
                "2026-09-19T06:00:00Z", "2026-09-19T16:01:00Z"));
        assertEquals("ILLEGAL", candidate.overallResult());
        List<String> reasons = candidate.results().get(0).reasons();
        assertTrue(reasons.contains(ViolationReasons.WEEKLY_DUTY_EXCEEDED));
        assertFalse(reasons.contains(ViolationReasons.REST_PERIOD_SHORT));
        // 驳回记录不受理，不计入后续工时。
        assertFalse(candidate.results().get(0).accepted());
    }

    // ------------------------------------------------------------------
    // 休息期 10 小时边界
    // ------------------------------------------------------------------

    @Test
    void restPeriod_exactlyTenHoursLegal() {
        checkOne(duty("r-rest-base", "C003", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        // 次日 02:00 报到：与前班结束恰好相隔 10 小时。
        CheckResponse next = checkOne(duty("r-rest-ok", "C003", FLIGHT_UTC,
                "2026-09-21T02:00:00Z", "2026-09-21T12:00:00Z"));
        assertEquals("LEGAL", next.overallResult());
    }

    @Test
    void restPeriod_oneMinuteShortIllegal() {
        checkOne(duty("r-rest2-base", "C003", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        // 次日 01:59 报到：仅休息 9 小时 59 分。
        CheckResponse next = checkOne(duty("r-rest-no", "C003", FLIGHT_UTC,
                "2026-09-21T01:59:00Z", "2026-09-21T11:59:00Z"));
        assertEquals("ILLEGAL", next.overallResult());
        assertTrue(next.results().get(0).reasons().contains(ViolationReasons.REST_PERIOD_SHORT));
    }

    // ------------------------------------------------------------------
    // 同键幂等：同键同参重放、同键异参 409 且不写入
    // ------------------------------------------------------------------

    @Test
    void sameKeySameParams_returnsFirstJudgement() {
        MemberDutyRequest d = duty("r-idem", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z");
        CheckResponse first = service.check(new CheckRequest(List.of(d)));
        CheckResponse replay = service.check(new CheckRequest(List.of(d)));

        assertEquals(first.batchId(), replay.batchId());
        assertEquals(first.results().get(0).result(), replay.results().get(0).result());
        assertTrue(replay.results().get(0).idempotentReplay());
        assertFalse(first.results().get(0).idempotentReplay());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duty_record", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM check_batch", Integer.class));
    }

    @Test
    void sameKeyDifferentParams_conflict409AndNothingWritten() {
        checkOne(duty("r-conflict", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        assertThrows(ConflictException.class, () -> checkOne(duty("r-conflict", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T17:00:00Z")));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duty_record WHERE request_id = 'r-conflict'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM check_batch", Integer.class));
    }

    // ------------------------------------------------------------------
    // 整组一致：任一成员违规则整组违规，成员状态与整组结论一致
    // ------------------------------------------------------------------

    @Test
    void groupJudgement_anyIllegalMakesWholeGroupIllegal() {
        CheckResponse resp = service.check(new CheckRequest(List.of(
                duty("g-ok", "C001", FLIGHT_UTC,
                        "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"),
                duty("g-no", "C002", FLIGHT_UTC,
                        "2026-09-20T06:00:00Z", "2026-09-20T19:01:00Z"))));

        assertEquals("ILLEGAL", resp.overallResult());
        assertEquals("LEGAL", resp.results().get(0).result());
        assertTrue(resp.results().get(0).accepted());
        assertEquals("ILLEGAL", resp.results().get(1).result());
        assertFalse(resp.results().get(1).accepted());

        String dbOverall = jdbcTemplate.queryForObject(
                "SELECT overall_result FROM check_batch WHERE batch_id = ?",
                String.class, resp.batchId());
        assertEquals("ILLEGAL", dbOverall);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duty_record WHERE batch_id = "
                        + "(SELECT id FROM check_batch WHERE batch_id = ?)",
                Integer.class, resp.batchId()));
    }

    // ------------------------------------------------------------------
    // 封板终态：重复封板幂等；封板后延误仅迟到留痕
    // ------------------------------------------------------------------

    @Test
    void sealedBatch_isFinal_lateDelayDoesNotOverturn() {
        CheckResponse first = checkOne(duty("r-seal", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        String batchId = first.batchId();

        SealResponse sealed = service.seal(batchId);
        assertTrue(sealed.sealed());
        assertFalse(sealed.idempotentReplay());

        SealResponse resealed = service.seal(batchId);
        assertTrue(resealed.sealed());
        assertTrue(resealed.idempotentReplay());

        // 封板后补交延误：只记迟到事件，结论与实际结束时刻不变。
        DelayResponse late = service.reportDelay(new DelayRequest(
                "d-late", "r-seal", 120, "封板后补报"));
        assertTrue(late.late());
        assertFalse(late.accepted());
        assertEquals("LEGAL", late.result());
        assertEquals(0, late.totalExtensionMinutes());
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), late.dutyEnd());

        Boolean lateFlag = jdbcTemplate.queryForObject(
                "SELECT late FROM delay_event WHERE request_id = 'd-late'", Boolean.class);
        assertEquals(Boolean.TRUE, lateFlag);
        String overall = jdbcTemplate.queryForObject(
                "SELECT overall_result FROM check_batch WHERE batch_id = ?", String.class, batchId);
        assertEquals("LEGAL", overall);
    }

    @Test
    void delayBeforeSeal_isAcceptedAndRefreshesBatch() {
        CheckResponse first = checkOne(duty("r-preseal", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        DelayResponse delayed = service.reportDelay(new DelayRequest(
                "d-preseal", "r-preseal", 30, null));
        assertTrue(delayed.accepted());
        assertEquals(30, delayed.totalExtensionMinutes());

        SealResponse sealed = service.seal(first.batchId());
        assertTrue(sealed.sealed());
        assertEquals("LEGAL", sealed.overallResult());

        // 已封板后再次补报仍只留痕。
        DelayResponse late = service.reportDelay(new DelayRequest(
                "d-preseal-late", "r-preseal", 30, null));
        assertTrue(late.late());
        assertEquals(30, late.totalExtensionMinutes());
    }

    @Test
    void sameDelayRequestId_replaysFirstOutcome() {
        checkOne(duty("r-dly-idem", "C001", FLIGHT_UTC,
                "2026-09-20T06:00:00Z", "2026-09-20T16:00:00Z"));
        DelayRequest req = new DelayRequest("d-idem", "r-dly-idem", 30, null);
        DelayResponse first = service.reportDelay(req);
        DelayResponse replay = service.reportDelay(req);
        assertTrue(first.accepted());
        assertTrue(replay.idempotentReplay());
        assertEquals(30, replay.totalExtensionMinutes());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delay_event WHERE request_id = 'd-idem'", Integer.class));
    }
}
