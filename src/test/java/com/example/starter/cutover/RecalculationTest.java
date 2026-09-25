package com.example.starter.cutover;

import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ReportRequest;
import com.example.starter.cutover.api.RollbackRecordView;
import com.example.starter.cutover.service.CutoverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可复算性：影响分析清单、步骤时序、回退判据命中记录（含快照）均可只读复算。
 */
class RecalculationTest extends AbstractCutoverTest {

    @Autowired
    private CutoverService cutoverService;

    @Test
    void rollbackRecordIsRecoverableWithSnapshotAndStepSequence() {
        ApplicationResponse app = cutoverService.apply(
                applyRequest("REQ-REPLAY", "DEV-CORE-SW-01", "NORMAL",
                        BASE_TIME, BASE_TIME.plusHours(4), defaultSteps()));
        Long id = app.id();
        cutoverService.analyze(id);
        cutoverService.approve(id);
        cutoverService.execute(id);
        cutoverService.report(id, 1, new ReportRequest("R-S1", "START", null));
        cutoverService.report(id, 1, new ReportRequest("R-R1", "RESULT", "SUCCESS"));
        cutoverService.report(id, 2, new ReportRequest("R-S2", "START", null));
        cutoverService.report(id, 2, new ReportRequest("R-R2", "RESULT", "HARD_FAIL"));

        RollbackRecordView view = cutoverService.getRollbackRecord(id);
        assertTrue(view.exists());
        assertEquals("HARD_CHECK_FAILED", view.triggerReason());
        assertEquals(2, view.stepSeq());
        assertNotNull(view.rolledBackAt());
        assertNotNull(view.snapshotJson());
        // 快照冻结了回退时点的全部步骤状态，可逐序复算
        assertTrue(view.snapshotJson().contains("\"seq\":1"));
        assertTrue(view.snapshotJson().contains("\"seq\":2"));
        assertTrue(view.snapshotJson().contains("\"seq\":3"));

        // 步骤时序可复算：1 DONE / 2 FAILED / 3 FROZEN
        ApplicationResponse detail = cutoverService.getApplication(id);
        assertEquals("DONE", detail.steps().get(0).status());
        assertEquals("FAILED", detail.steps().get(1).status());
        assertEquals("FROZEN", detail.steps().get(2).status());
    }

    @Test
    void noRollbackRecordWhenVerified() {
        ApplicationResponse app = cutoverService.apply(
                applyRequest("REQ-REPLAY-OK", "DEV-FW-01", "NORMAL",
                        BASE_TIME, BASE_TIME.plusHours(2), defaultSteps()));
        Long id = app.id();
        cutoverService.analyze(id);
        cutoverService.approve(id);
        cutoverService.execute(id);
        for (int seq = 1; seq <= 3; seq++) {
            cutoverService.report(id, seq, new ReportRequest("S" + seq, "START", null));
            cutoverService.report(id, seq, new ReportRequest("R" + seq, "RESULT", "SUCCESS"));
        }
        RollbackRecordView view = cutoverService.getRollbackRecord(id);
        assertFalse(view.exists());

        // 影响分析清单仍可复算（终态前的设备关联业务全量列出）
        var analysis = cutoverService.viewAnalysis(id);
        assertEquals(java.util.List.of("BIZ-PAY", "BIZ-RISK"), analysis.affectedBusinesses());
    }
}
