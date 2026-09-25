package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.DeclareResponse;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.domain.PreQuarantineResult;
import com.example.starter.slaughter.service.ServiceOutcome;
import com.example.starter.slaughter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宰前申报 6 小时（UTC）有效期：恰满 6 小时未屠宰须重新申报。
 */
class DeclareWindowTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002", "H003");

    @Test
    void declare_setsValidUntilExactlySixHoursUtc() {
        ServiceOutcome<DeclareResponse> outcome = service.declare(
                new DeclareRequest("req-declare-1", "B-WIN-1", heads));

        assertEquals(HttpStatus.CREATED.value(), outcome.httpStatus());
        assertFalse(outcome.replayed());
        assertEquals(BatchStatus.DECLARED.name(), outcome.data().status());
        assertEquals(outcome.data().declaredAtUtc().plusHours(6),
                outcome.data().validUntilUtc());
    }

    @Test
    void quarantineAndSlaughterAllowedJustBeforeSixHours() {
        service.declare(new DeclareRequest("req-declare-2", "B-WIN-2", heads));

        // 恰满前 1 秒：宰前检疫仍有效。
        clock.advance(Duration.ofHours(6).minusSeconds(1));
        var pq = service.preQuarantine("B-WIN-2",
                new PreQuarantineRequest("req-pq-2", PreQuarantineResult.PASS));
        assertEquals(BatchStatus.PRE_QUARANTINED.name(), pq.data().status());
    }

    @Test
    void quarantineExactlyAtSixHoursIsRejectedAndRedeclareSucceeds() {
        service.declare(new DeclareRequest("req-declare-3", "B-WIN-3", heads));

        // 恰满 6 小时：检疫失效。
        clock.advance(Duration.ofHours(6));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.preQuarantine(
                "B-WIN-3", new PreQuarantineRequest("req-pq-3", PreQuarantineResult.PASS)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("QUARANTINE_WINDOW_EXPIRED", ex.getCode());

        // 未屠宰，同批次同头只重新申报受理，重新申报次数递增。
        ServiceOutcome<DeclareResponse> redeclared = service.declare(
                new DeclareRequest("req-redeclare-3", "B-WIN-3", heads));
        assertFalse(redeclared.replayed());
        assertEquals(1, redeclared.data().redeclareNo());
        assertEquals(BatchStatus.DECLARED.name(), redeclared.data().status());

        // 重新申报后检疫恢复可办。
        var pq = service.preQuarantine("B-WIN-3",
                new PreQuarantineRequest("req-pq-3b", PreQuarantineResult.PASS));
        assertEquals(BatchStatus.PRE_QUARANTINED.name(), pq.data().status());
    }

    @Test
    void redeclareWithinWindowConflicts() {
        service.declare(new DeclareRequest("req-declare-4", "B-WIN-4", heads));
        clock.advance(Duration.ofHours(5));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.declare(
                new DeclareRequest("req-redeclare-4", "B-WIN-4", heads)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("BATCH_ALREADY_DECLARED", ex.getCode());
    }

    @Test
    void duplicateHeadNoInDeclareRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.declare(
                new DeclareRequest("req-declare-5", "B-WIN-5", List.of("H001", "H001"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("DUPLICATE_HEAD_NO", ex.getCode());
    }

    @Test
    void redeclareWithDifferentHeadsRejected() {
        service.declare(new DeclareRequest("req-declare-6", "B-WIN-6", heads));
        clock.advance(Duration.ofHours(6));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.declare(
                new DeclareRequest("req-redeclare-6", "B-WIN-6", List.of("H001", "H002"))));
        assertEquals("HEAD_LIST_MISMATCH", ex.getCode());
        assertTrue(jdbc.queryForObject(
                "SELECT head_count FROM slaughter_batch WHERE batch_no = 'B-WIN-6'",
                Integer.class) == 3);
    }

    @Test
    void redeclareAfterSlaughterStartedRejected_evenAtSixHours() {
        declareAndPass("win7", "B-WIN-7", heads);
        service.registerInspection("B-WIN-7", new com.example.starter.slaughter.dto.InspectionRegisterRequest(
                "win7-insp",
                List.of(new com.example.starter.slaughter.dto.InspectionItem(
                        "H001", com.example.starter.slaughter.domain.InspectionResult.NORMAL))));

        // 已开宰：恰满 6 小时也不能重新申报（6 小时约束仅针对未屠宰）。
        clock.advance(Duration.ofHours(6));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.declare(
                new DeclareRequest("req-redeclare-7", "B-WIN-7", heads)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("SLAUGHTER_ALREADY_STARTED", ex.getCode());
        assertEquals("INSPECTING", jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = 'B-WIN-7'", String.class));
    }

    @Test
    void quarantineFailCanRedeclareAfterWindow() {
        service.declare(new DeclareRequest("req-declare-8", "B-WIN-8", heads));
        service.preQuarantine("B-WIN-8",
                new PreQuarantineRequest("req-pq-8", PreQuarantineResult.FAIL));
        clock.advance(Duration.ofHours(6));
        // 检疫不合格且窗口已满：允许重新申报开启新一轮流程。
        var redeclared = service.declare(new DeclareRequest("req-redeclare-8", "B-WIN-8", heads));
        assertEquals(BatchStatus.DECLARED.name(), redeclared.data().status());
        assertEquals(1, redeclared.data().redeclareNo());
    }
}
