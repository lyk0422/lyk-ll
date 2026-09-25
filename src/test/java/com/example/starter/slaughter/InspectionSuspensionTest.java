package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.dto.InspectionCompleteRequest;
import com.example.starter.slaughter.dto.InspectionItem;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.dto.JudgmentRequest;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.service.ServiceOutcome;
import com.example.starter.slaughter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步检验：按头只编号一一对应，漏检头只整批暂停流转，补检后恢复。
 */
class InspectionSuspensionTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002", "H003", "H004");

    @Test
    void missingHeadsSuspendWholeBatchAndResumeAfterSupplement() {
        declareAndPass("susp", "B-SUSP-1", heads);

        // 只登记 3/4 头只即收口：漏检 1 头，整批暂停。
        service.registerInspection("B-SUSP-1", new InspectionRegisterRequest("susp-insp-1",
                List.of(new InspectionItem("H001", InspectionResult.NORMAL),
                        new InspectionItem("H002", InspectionResult.SUSPECT),
                        new InspectionItem("H003", InspectionResult.NORMAL))));
        var firstComplete = service.completeInspection("B-SUSP-1",
                new InspectionCompleteRequest("susp-complete-1"));
        assertEquals(BatchStatus.SUSPENDED.name(), firstComplete.data().status());
        assertEquals(1, firstComplete.data().missingCount());
        assertFalse(firstComplete.data().complete());

        // 暂停状态不得判定分流。
        BusinessException ex = assertThrows(BusinessException.class, () -> service.judge(
                "B-SUSP-1", new JudgmentRequest("susp-judge-1", List.of())));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INVALID_BATCH_STATE", ex.getCode());

        // 补检漏检头只后再次收口：流转恢复为 INSPECTED。
        service.registerInspection("B-SUSP-1", new InspectionRegisterRequest("susp-insp-2",
                List.of(new InspectionItem("H004", InspectionResult.NORMAL))));
        var secondComplete = service.completeInspection("B-SUSP-1",
                new InspectionCompleteRequest("susp-complete-2"));
        assertEquals(BatchStatus.INSPECTED.name(), secondComplete.data().status());
        assertEquals(0, secondComplete.data().missingCount());
        assertTrue(secondComplete.data().complete());
    }

    @Test
    void unknownHeadRejected() {
        declareAndPass("unk", "B-SUSP-2", heads);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.registerInspection(
                "B-SUSP-2", new InspectionRegisterRequest("unk-insp",
                        List.of(new InspectionItem("H999", InspectionResult.NORMAL)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("UNKNOWN_HEAD", ex.getCode());
    }

    @Test
    void duplicateHeadInspectionRejected() {
        declareAndPass("dup", "B-SUSP-3", heads);
        service.registerInspection("B-SUSP-3", new InspectionRegisterRequest("dup-insp-1",
                List.of(new InspectionItem("H001", InspectionResult.NORMAL))));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.registerInspection(
                "B-SUSP-3", new InspectionRegisterRequest("dup-insp-2",
                        List.of(new InspectionItem("H001", InspectionResult.SUSPECT)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("HEAD_ALREADY_INSPECTED", ex.getCode());
    }

    @Test
    void inspectionBeforeQuarantineRejected() {
        service.declare(new com.example.starter.slaughter.dto.DeclareRequest(
                "raw-declare", "B-SUSP-4", heads));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.registerInspection(
                "B-SUSP-4", new InspectionRegisterRequest("raw-insp",
                        List.of(new InspectionItem("H001", InspectionResult.NORMAL)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void singleInspectionChainPerBatch_ledgerRecomputable() {
        declareAndPass("chain", "B-SUSP-5", heads);
        service.registerInspection("B-SUSP-5", new InspectionRegisterRequest("chain-insp-1",
                List.of(new InspectionItem("H001", InspectionResult.NORMAL),
                        new InspectionItem("H002", InspectionResult.NORMAL))));
        service.registerInspection("B-SUSP-5", new InspectionRegisterRequest("chain-insp-2",
                List.of(new InspectionItem("H003", InspectionResult.SUSPECT),
                        new InspectionItem("H004", InspectionResult.NORMAL))));

        // 同批次只有一条检验链，且计数可由头只台账复算一致。
        Integer chainCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_chain WHERE batch_no = 'B-SUSP-5'", Integer.class);
        assertEquals(1, chainCount);
        Integer ledgerInspected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-SUSP-5' AND inspection_result IS NOT NULL",
                Integer.class);
        Integer chainInspected = jdbc.queryForObject(
                "SELECT inspected_count FROM inspection_chain WHERE batch_no = 'B-SUSP-5'", Integer.class);
        assertEquals(4, ledgerInspected);
        assertEquals(ledgerInspected, chainInspected);
    }

    @Test
    void quarantineFailBlocksSlaughter() {
        service.declare(new com.example.starter.slaughter.dto.DeclareRequest(
                "fail-declare", "B-SUSP-6", heads));
        var pq = service.preQuarantine("B-SUSP-6",
                new PreQuarantineRequest("fail-pq",
                        com.example.starter.slaughter.domain.PreQuarantineResult.FAIL));
        assertEquals(BatchStatus.QUARANTINE_REJECTED.name(), pq.data().status());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.registerInspection(
                "B-SUSP-6", new InspectionRegisterRequest("fail-insp",
                        List.of(new InspectionItem("H001", InspectionResult.NORMAL)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }
}
