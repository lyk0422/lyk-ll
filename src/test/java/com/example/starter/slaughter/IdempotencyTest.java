package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.DeclareResponse;
import com.example.starter.slaughter.dto.InspectionCompleteRequest;
import com.example.starter.slaughter.dto.InspectionItem;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.domain.PreQuarantineResult;
import com.example.starter.slaughter.dto.ReceiptResponse;
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
 * requestId 幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409 且不改变批次状态。
 */
class IdempotencyTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002");

    @Test
    void sameKeySameParams_declareReplaysFirstResult() {
        DeclareRequest request = new DeclareRequest("idem-declare-1", "B-IDEM-1", heads);
        ServiceOutcome<DeclareResponse> first = service.declare(request);
        ServiceOutcome<DeclareResponse> replay = service.declare(request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.httpStatus(), replay.httpStatus());
        assertEquals(first.data(), replay.data());
        // 未产生第二条批次或头只台账。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM slaughter_batch WHERE batch_no = 'B-IDEM-1'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-IDEM-1'", Integer.class));
    }

    @Test
    void sameKeyDifferentParams_returns409AndLeavesStateUntouched() {
        service.declare(new DeclareRequest("idem-conflict", "B-IDEM-2", heads));

        DeclareRequest tampered = new DeclareRequest("idem-conflict", "B-IDEM-2",
                List.of("H001", "H003"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.declare(tampered));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("IDEMPOTENT_CONFLICT", ex.getCode());

        // 状态与头只台账均未被异参请求改变。
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-IDEM-2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-IDEM-2' AND head_no = 'H003'",
                Integer.class));
    }

    @Test
    void sameKeySameParams_inspectionReplayDoesNotDoubleCount() {
        declareAndPass("idem-insp", "B-IDEM-3", heads);
        InspectionRegisterRequest request = new InspectionRegisterRequest("idem-insp-req",
                List.of(new InspectionItem("H001", InspectionResult.NORMAL),
                        new InspectionItem("H002", InspectionResult.SUSPECT)));
        var first = service.registerInspection("B-IDEM-3", request);
        var replay = service.registerInspection("B-IDEM-3", request);

        assertTrue(replay.replayed());
        assertEquals(first.data(), replay.data());
        assertEquals(2, jdbc.queryForObject(
                "SELECT inspected_count FROM inspection_chain WHERE batch_no = 'B-IDEM-3'",
                Integer.class));
    }

    @Test
    void sameKeyDifferentInspectionResult_returns409() {
        declareAndPass("idem-insp2", "B-IDEM-4", heads);
        service.registerInspection("B-IDEM-4", new InspectionRegisterRequest("idem-insp2-req",
                List.of(new InspectionItem("H001", InspectionResult.NORMAL))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.registerInspection("B-IDEM-4",
                        new InspectionRegisterRequest("idem-insp2-req",
                                List.of(new InspectionItem("H001", InspectionResult.SUSPECT)))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("IDEMPOTENT_CONFLICT", ex.getCode());
        assertEquals("NORMAL", jdbc.queryForObject(
                "SELECT inspection_result FROM batch_head WHERE batch_no = 'B-IDEM-4' AND head_no = 'H001'",
                String.class));
    }

    @Test
    void sameKeyDifferentPathBatch_returns409() {
        service.declare(new DeclareRequest("idem-pq-declare-1", "B-IDEM-5A", heads));
        service.declare(new DeclareRequest("idem-pq-declare-2", "B-IDEM-5B", heads));
        service.preQuarantine("B-IDEM-5A",
                new PreQuarantineRequest("idem-pq-req", PreQuarantineResult.PASS));

        // 同一 requestId 用于另一批次：路径变量属于业务字段，指纹不同，409。
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.preQuarantine("B-IDEM-5B",
                        new PreQuarantineRequest("idem-pq-req", PreQuarantineResult.PASS)));
        assertEquals("IDEMPOTENT_CONFLICT", ex.getCode());
        assertEquals("DECLARED", jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = 'B-IDEM-5B'", String.class));
    }

    @Test
    void sameKeySameParams_receiptReplaysFirstVerdict() {
        declareAndPass("idem-rcp", "B-IDEM-6", heads);
        inspectAllAndComplete("idem-rcp", "B-IDEM-6", heads);
        service.judge("B-IDEM-6", judgment("idem-rcp-judge", List.of(
                judge("H001", JudgmentCategory.RENDERED),
                judge("H002", JudgmentCategory.RENDERED))));

        var request = receipt("idem-rcp-req", "RCP-IDEM", heads);
        ServiceOutcome<ReceiptResponse> first = service.registerReceipt("B-IDEM-6", request);
        ServiceOutcome<ReceiptResponse> replay = service.registerReceipt("B-IDEM-6", request);

        assertTrue(first.data().accepted());
        assertTrue(replay.replayed());
        assertEquals(first.data(), replay.data());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposal_receipt WHERE receipt_no = 'RCP-IDEM'", Integer.class));
    }

    @Test
    void sameKeyDifferentReceiptHeads_returns409() {
        declareAndPass("idem-rcp2", "B-IDEM-7", heads);
        inspectAllAndComplete("idem-rcp2", "B-IDEM-7", heads);
        service.judge("B-IDEM-7", judgment("idem-rcp2-judge", List.of(
                judge("H001", JudgmentCategory.RENDERED),
                judge("H002", JudgmentCategory.RENDERED))));
        service.registerReceipt("B-IDEM-7", receipt("idem-rcp2-req", "RCP-IDEM-7", heads));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.registerReceipt("B-IDEM-7",
                        receipt("idem-rcp2-req", "RCP-IDEM-7B", List.of("H001"))));
        assertEquals("IDEMPOTENT_CONFLICT", ex.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposal_receipt WHERE receipt_no = 'RCP-IDEM-7B'",
                Integer.class));
    }

    @Test
    void completionRequestIdIsAlsoIdempotent() {
        declareAndPass("idem-done", "B-IDEM-8", heads);
        service.registerInspection("B-IDEM-8", new InspectionRegisterRequest("idem-done-insp",
                heads.stream().map(h -> new InspectionItem(h, InspectionResult.NORMAL)).toList()));
        var first = service.completeInspection("B-IDEM-8",
                new InspectionCompleteRequest("idem-done-req"));
        var replay = service.completeInspection("B-IDEM-8",
                new InspectionCompleteRequest("idem-done-req"));
        assertTrue(replay.replayed());
        assertEquals(first.data(), replay.data());
    }
}
