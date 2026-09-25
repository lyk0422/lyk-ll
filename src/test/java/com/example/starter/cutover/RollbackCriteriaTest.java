package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ReportRequest;
import com.example.starter.cutover.api.ReportResponse;
import com.example.starter.cutover.api.RollbackResponse;
import com.example.starter.cutover.service.CutoverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回退判据：验证点恰满 10 分钟超时、硬性检查失败、普通步骤失败，
 * 以及回退指令与原步骤在同一事务内冻结后续步骤的原子性。
 */
class RollbackCriteriaTest extends AbstractCutoverTest {

    @Autowired
    private CutoverService cutoverService;

    private Long prepareExecuting(String requestId, LocalDateTime start, LocalDateTime end) {
        ApplicationResponse app = cutoverService.apply(
                applyRequest(requestId, "DEV-CORE-SW-01", "NORMAL", start, end, defaultSteps()));
        cutoverService.analyze(app.id());
        cutoverService.approve(app.id());
        cutoverService.execute(app.id());
        return app.id();
    }

    private void startStep(long id, int seq, String requestId) {
        cutoverService.report(id, seq, new ReportRequest(requestId, "START", null));
    }

    private ReportResponse completeStep(long id, int seq, String requestId, String result) {
        return cutoverService.report(id, seq, new ReportRequest(requestId, "RESULT", result));
    }

    @Test
    void verifyPointTimeoutAtExactlyTenMinutesTriggersRollback() {
        Long id = prepareExecuting("REQ-TIMEOUT", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-1S");
        completeStep(id, 1, "R-1R", "SUCCESS");
        startStep(id, 2, "R-2S");
        completeStep(id, 2, "R-2R", "SUCCESS");
        // 10:00 开始关键验证点
        startStep(id, 3, "R-3S");

        // 9 分 59 秒未超时
        advanceSeconds(9 * 60 + 59);
        RollbackResponse before = cutoverService.evaluate(id);
        assertEquals("EXECUTING", before.applicationStatus());
        assertEquals(null, before.triggerReason());

        // 恰满 10 分钟判定超时并自动回退
        advanceSeconds(1);
        RollbackResponse hit = cutoverService.evaluate(id);
        assertEquals("ROLLED_BACK", hit.applicationStatus());
        assertEquals("VERIFY_TIMEOUT", hit.triggerReason());
        assertEquals(1, hit.frozenStepCount(), "仅验证点本身处于 RUNNING，冻结 1 步");

        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("ROLLED_BACK", detail.status());
        assertEquals("DONE", detail.steps().get(0).status());
        assertEquals("DONE", detail.steps().get(1).status());
        assertEquals("FROZEN", detail.steps().get(2).status());
    }

    @Test
    void successReportAtExactlyTenMinutesIsAlsoTimeout() {
        Long id = prepareExecuting("REQ-TIMEOUT-RESULT", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-1S");
        completeStep(id, 1, "R-1R", "SUCCESS");
        startStep(id, 2, "R-2S");
        completeStep(id, 2, "R-2R", "SUCCESS");
        startStep(id, 3, "R-3S");
        // 恰满 10 分钟才回报成功：仍判超时，回退优先
        advanceMinutes(10);
        ReportResponse resp = completeStep(id, 3, "R-3R", "SUCCESS");
        assertTrue(resp.rollbackTriggered());
        assertEquals("VERIFY_TIMEOUT", resp.rollbackReason());
        assertEquals("ROLLED_BACK", resp.applicationStatus());
    }

    @Test
    void successReportWithinTenMinutesPasses() {
        Long id = prepareExecuting("REQ-VERIFY-OK", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-1S");
        completeStep(id, 1, "R-1R", "SUCCESS");
        startStep(id, 2, "R-2S");
        completeStep(id, 2, "R-2R", "SUCCESS");
        startStep(id, 3, "R-3S");
        advanceSeconds(9 * 60 + 59);
        ReportResponse resp = completeStep(id, 3, "R-3R", "SUCCESS");
        assertEquals("VERIFIED", resp.applicationStatus());
    }

    @Test
    void hardCheckFailureTriggersRollbackAndFreezesRemainingStepsAtomically() {
        Long id = prepareExecuting("REQ-HARD-FAIL", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-1S");
        completeStep(id, 1, "R-1R", "SUCCESS");
        startStep(id, 2, "R-2S");
        // 步骤 2 硬性检查失败：同事务冻结步骤 2 与步骤 3，不得部分继续
        ReportResponse resp = completeStep(id, 2, "R-2R", "HARD_FAIL");
        assertTrue(resp.rollbackTriggered());
        assertEquals("HARD_CHECK_FAILED", resp.rollbackReason());
        assertEquals("ROLLED_BACK", resp.applicationStatus());

        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("ROLLED_BACK", detail.status());
        assertEquals("DONE", detail.steps().get(0).status());
        assertEquals("FAILED", detail.steps().get(1).status());
        assertEquals("FROZEN", detail.steps().get(2).status());
        // 回退记录唯一且判据可复算
        Integer rollbackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rollback_record WHERE cutover_id = ?", Integer.class, id);
        assertEquals(1, rollbackCount);
        String reason = jdbcTemplate.queryForObject(
                "SELECT trigger_reason FROM rollback_record WHERE cutover_id = ?", String.class, id);
        assertEquals("HARD_CHECK_FAILED", reason);
    }

    @Test
    void normalStepFailureAlsoRollsBack() {
        Long id = prepareExecuting("REQ-STEP-FAIL", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-1S");
        ReportResponse resp = completeStep(id, 1, "R-1R", "FAIL");
        assertTrue(resp.rollbackTriggered());
        assertEquals("STEP_FAILED", resp.rollbackReason());
        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("ROLLED_BACK", detail.status());
        assertEquals("FAILED", detail.steps().get(0).status());
        assertEquals("FROZEN", detail.steps().get(1).status());
        assertEquals("FROZEN", detail.steps().get(2).status());
    }

    @Test
    void frozenStepsCannotBeReportedAfterRollback() {
        Long id = prepareExecuting("REQ-FROZEN", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-1S");
        completeStep(id, 1, "R-1R", "FAIL");
        // 回退终态后补报步骤：只记迟到事件，不产生新步骤记录
        ReportResponse late = completeStep(id, 2, "R-2R", "SUCCESS");
        assertTrue(late.lateEvent());
        assertEquals("ROLLED_BACK", late.applicationStatus());
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM step_report_event WHERE cutover_id = ?", Integer.class, id);
        assertEquals(1, events);
        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("FROZEN", detail.steps().get(1).status());
    }
}
