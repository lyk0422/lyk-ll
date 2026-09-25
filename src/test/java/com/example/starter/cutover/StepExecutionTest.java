package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplyRequest;
import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ReportRequest;
import com.example.starter.cutover.api.ReportResponse;
import com.example.starter.cutover.api.RollbackResponse;
import com.example.starter.cutover.service.CutoverService;
import com.example.starter.cutover.support.BusinessValidationException;
import com.example.starter.cutover.support.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 步骤按序执行、逐条回报、窗口终点口径与窗口超期强制回退。
 */
class StepExecutionTest extends AbstractCutoverTest {

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
    void stepsMustExecuteInSequence() {
        Long id = prepareExecuting("REQ-SEQ", BASE_TIME, BASE_TIME.plusHours(2));

        // 未完成步骤 1 不能直接开始步骤 2
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> startStep(id, 2, "R-S2-START"));
        assertTrue(ex.getMessage().contains("前序步骤 1 尚未完成"));

        startStep(id, 1, "R-S1-START");
        completeStep(id, 1, "R-S1-OK", "SUCCESS");
        startStep(id, 2, "R-S2-START");
        completeStep(id, 2, "R-S2-OK", "SUCCESS");
        startStep(id, 3, "R-S3-START");
        ReportResponse last = completeStep(id, 3, "R-S3-OK", "SUCCESS");

        assertEquals("DONE", last.stepStatus());
        assertEquals("VERIFIED", last.applicationStatus());
        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("VERIFIED", detail.status());
        detail.steps().forEach(s -> assertEquals("DONE", s.status()));
    }

    @Test
    void cannotReportResultBeforeStart() {
        Long id = prepareExecuting("REQ-NOSTART", BASE_TIME, BASE_TIME.plusHours(2));
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> completeStep(id, 1, "R-X", "SUCCESS"));
        assertTrue(ex.getMessage().contains("尚未 START"));
    }

    @Test
    void duplicateStartOfSameStepRejected() {
        Long id = prepareExecuting("REQ-DUPSTART", BASE_TIME, BASE_TIME.plusHours(2));
        startStep(id, 1, "R-A");
        assertThrows(ConflictException.class, () -> startStep(id, 1, "R-B"));
    }

    @Test
    void completionExactlyAtWindowEndIsValid() {
        // 窗口 [10:00,14:00)，恰在 14:00 完成最后一步有效
        Long id = prepareExecuting("REQ-ENDPOINT", BASE_TIME, BASE_TIME.plusHours(4));
        startStep(id, 1, "R-S1-START");
        advanceMinutes(10);
        completeStep(id, 1, "R-S1-OK", "SUCCESS");
        startStep(id, 2, "R-S2-START");
        advanceMinutes(10);
        completeStep(id, 2, "R-S2-OK", "SUCCESS");
        // 验证点在终点前 5 分钟开始，恰终点回报，间隔 5 分钟（未超时）且恰在窗口终点（有效）
        setClock(BASE_TIME.plusHours(4).minusMinutes(5));
        startStep(id, 3, "R-S3-START");
        setClock(BASE_TIME.plusHours(4));
        ReportResponse last = completeStep(id, 3, "R-S3-OK", "SUCCESS");
        assertEquals("DONE", last.stepStatus());
        assertEquals("VERIFIED", last.applicationStatus());
        assertFalse(last.rollbackTriggered());
    }

    @Test
    void unfinishedStepsAfterWindowEndBecomeOverdueAndRollback() {
        Long id = prepareExecuting("REQ-OVERDUE", BASE_TIME, BASE_TIME.plusHours(2));
        startStep(id, 1, "R-S1-START");
        advanceMinutes(30);
        completeStep(id, 1, "R-S1-OK", "SUCCESS");
        startStep(id, 2, "R-S2-START");
        // 越过窗口终点后巡检：仍有 RUNNING/PENDING 步骤
        setClock(BASE_TIME.plusHours(2).plusSeconds(1));
        RollbackResponse rb = cutoverService.evaluate(id);
        assertEquals("WINDOW_OVERDUE", rb.applicationStatus());
        assertEquals("WINDOW_OVERDUE", rb.triggerReason());
        assertTrue(rb.frozenStepCount() >= 1, "应冻结未完成步骤");

        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("WINDOW_OVERDUE", detail.status());
        // 步骤 1 DONE，步骤 2、3 冻结，不存在“部分步骤继续执行”
        assertEquals("DONE", detail.steps().get(0).status());
        assertEquals("FROZEN", detail.steps().get(1).status());
        assertEquals("FROZEN", detail.steps().get(2).status());
    }

    @Test
    void allFinishedExactlyAtWindowEndIsNotOverdue() {
        Long id = prepareExecuting("REQ-ALLDONE", BASE_TIME, BASE_TIME.plusHours(1));
        startStep(id, 1, "R-1S");
        completeStep(id, 1, "R-1R", "SUCCESS");
        startStep(id, 2, "R-2S");
        completeStep(id, 2, "R-2R", "SUCCESS");
        // 验证点在恰终点开始并立即通过，间隔 0 分钟，不构成超时
        setClock(BASE_TIME.plusHours(1));
        startStep(id, 3, "R-3S");
        ReportResponse last = completeStep(id, 3, "R-3R", "SUCCESS");
        assertEquals("VERIFIED", last.applicationStatus());
        // 恰终点已全部完成，巡检不判超期
        RollbackResponse eval = cutoverService.evaluate(id);
        assertEquals("VERIFIED", eval.applicationStatus());
        assertEquals(null, eval.triggerReason());
    }
}
