package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ApplyRequest;
import com.example.starter.cutover.api.ReportRequest;
import com.example.starter.cutover.api.ReportResponse;
import com.example.starter.cutover.api.SealResponse;
import com.example.starter.cutover.service.CutoverService;
import com.example.starter.cutover.support.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等与封板终态：同键同参返回首次结果、同键异参 409 且不产生新记录、
 * 重复封板幂等成功、封板后补报只记迟到事件。
 */
class IdempotencyAndSealTest extends AbstractCutoverTest {

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

    @Test
    void applySameKeySameParamsReturnsFirstResult() {
        ApplyRequest req = applyRequest("REQ-IDEM", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(2), defaultSteps());
        ApplicationResponse first = cutoverService.apply(req);
        ApplicationResponse replay = cutoverService.apply(req);
        assertEquals(first.id(), replay.id());
        assertTrue(replay.replay());
        assertFalse(first.replay());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cutover_application WHERE request_id = 'REQ-IDEM'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void applySameKeyDifferentParamsReturns409() {
        cutoverService.apply(applyRequest("REQ-IDEM2", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(2), defaultSteps()));
        ApplyRequest mutated = applyRequest("REQ-IDEM2", "DEV-CORE-SW-01", "NORMAL",
                BASE_TIME, BASE_TIME.plusHours(3), defaultSteps());
        ConflictException ex = assertThrows(ConflictException.class,
                () -> cutoverService.apply(mutated));
        assertEquals("IDEMPOTENCY_PARAM_MISMATCH", ex.getCode());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cutover_application WHERE request_id = 'REQ-IDEM2'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void reportSameKeySameParamsReturnsFirstResult() {
        Long id = prepareExecuting("REQ-RIDEM", BASE_TIME, BASE_TIME.plusHours(4));
        ReportRequest start = new ReportRequest("R-START-1", "START", null);
        ReportResponse first = cutoverService.report(id, 1, start);
        ReportResponse replay = cutoverService.report(id, 1, start);
        assertEquals(first.stepStatus(), replay.stepStatus());
        assertTrue(replay.replay());
        assertFalse(first.replay());
        // 不产生新步骤记录：步骤状态仍为首次 START 后的 RUNNING
        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("RUNNING", detail.steps().get(0).status());
    }

    @Test
    void reportSameKeyDifferentParamsReturns409AndNoNewRecord() {
        Long id = prepareExecuting("REQ-RIDEM2", BASE_TIME, BASE_TIME.plusHours(4));
        cutoverService.report(id, 1, new ReportRequest("R-START-2", "START", null));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> cutoverService.report(id, 1, new ReportRequest("R-START-2", "RESULT", "SUCCESS")));
        assertEquals("IDEMPOTENCY_PARAM_MISMATCH", ex.getCode());
        // 不产生新步骤记录：步骤 1 仍为 RUNNING（未因异参重放而 DONE）
        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("RUNNING", detail.steps().get(0).status());
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'R-START-2'",
                Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    void sealIsTerminalAndRepeatSealIsIdempotent() {
        Long id = prepareExecuting("REQ-SEAL", BASE_TIME, BASE_TIME.plusHours(4));
        for (int seq = 1; seq <= 3; seq++) {
            cutoverService.report(id, seq, new ReportRequest("R-S" + seq, "START", null));
            cutoverService.report(id, seq, new ReportRequest("R-R" + seq, "RESULT", "SUCCESS"));
        }
        SealResponse first = cutoverService.seal(id);
        assertEquals("SEALED", first.status());
        assertFalse(first.replay());

        // 重复封板幂等成功
        SealResponse again = cutoverService.seal(id);
        assertEquals("SEALED", again.status());
        assertTrue(again.replay());

        // 封板后补报步骤只记迟到事件，状态不变
        ReportResponse late = cutoverService.report(id, 3,
                new ReportRequest("R-LATE", "RESULT", "SUCCESS"));
        assertTrue(late.lateEvent());
        assertEquals("SEALED", late.applicationStatus());
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM step_report_event WHERE cutover_id = ?", Integer.class, id);
        assertEquals(1, events);
        assertEquals("SEALED", cutoverService.getApplication(id).status());
    }

    @Test
    void sealAfterRollbackIsRejected() {
        Long id = prepareExecuting("REQ-SEAL-RB", BASE_TIME, BASE_TIME.plusHours(4));
        cutoverService.report(id, 1, new ReportRequest("R-S1", "START", null));
        cutoverService.report(id, 1, new ReportRequest("R-R1", "RESULT", "FAIL"));
        ConflictException ex = assertThrows(ConflictException.class, () -> cutoverService.seal(id));
        assertEquals("TERMINAL_CONFLICT", ex.getCode());
    }

    @Test
    void sealBeforeVerifyRejected() {
        Long id = prepareExecuting("REQ-SEAL-EARLY", BASE_TIME, BASE_TIME.plusHours(4));
        assertThrows(com.example.starter.cutover.support.BusinessValidationException.class,
                () -> cutoverService.seal(id));
    }
}
