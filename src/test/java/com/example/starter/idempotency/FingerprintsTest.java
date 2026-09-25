package com.example.starter.idempotency;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.IssueRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹测试：同键同参指纹一致，任一字段变化指纹不同，集合字段顺序不影响指纹。
 */
class FingerprintsTest {

    private DeclareRequest declare(String requestId, String batchNo, String potency) {
        return new DeclareRequest(requestId, "生物制品厂", "重组疫苗", batchNo,
                List.of("REGISTER_CERT", "BATCH_RECORD"),
                List.of(new InspectionInput("POTENCY", potency)));
    }

    @Test
    void sameKeySameParametersHaveSameFingerprint() {
        assertEquals(Fingerprints.declare(declare("req-1", "B2026001", "4.5")),
                Fingerprints.declare(declare("req-1", "B2026001", "4.5")));
    }

    @Test
    void documentOrderDoesNotChangeFingerprint() {
        DeclareRequest a = new DeclareRequest("req-1", "厂", "苗", "B1",
                List.of("REGISTER_CERT", "BATCH_RECORD"), List.of());
        DeclareRequest b = new DeclareRequest("req-1", "厂", "苗", "B1",
                List.of("BATCH_RECORD", "REGISTER_CERT"), List.of());
        // inspections 为空会在业务层被拒绝，但指纹归一化逻辑与此无关；补充同构非空检验以保持可比
        DeclareRequest a2 = withInspection(a);
        DeclareRequest b2 = withInspection(b);
        assertEquals(Fingerprints.declare(a2), Fingerprints.declare(b2));
    }

    @Test
    void differentBusinessFieldProducesDifferentFingerprint() {
        String base = Fingerprints.declare(declare("req-1", "B2026001", "4.5"));
        assertNotEquals(base, Fingerprints.declare(declare("req-1", "B2026001", "4.6")),
                "实测值变化必须导致指纹不同");
        assertNotEquals(base, Fingerprints.declare(declare("req-1", "B2026002", "4.5")),
                "批号变化必须导致指纹不同");
        assertNotEquals(base, Fingerprints.declare(declare("req-2", "B2026001", "4.5")),
                "requestId 变化必须导致指纹不同");
    }

    @Test
    void correctAndIssueFingerprintsVaryWithParameters() {
        CorrectRequest correct = new CorrectRequest("req-9", List.of("REGISTER_CERT"));
        assertEquals(Fingerprints.correct("B1", correct), Fingerprints.correct("B1", correct));
        assertNotEquals(Fingerprints.correct("B1", correct),
                Fingerprints.correct("B2", correct));
        IssueRequest issue = new IssueRequest("req-10");
        assertEquals(Fingerprints.issue("B1", issue), Fingerprints.issue("B1", issue));
        assertNotEquals(Fingerprints.issue("B1", issue), Fingerprints.issue("B2", issue));
    }

    private DeclareRequest withInspection(DeclareRequest source) {
        return new DeclareRequest(source.requestId(), source.enterpriseName(), source.productName(),
                source.batchNo(), source.documents(),
                List.of(new InspectionInput("POTENCY", "4.5"),
                        new InspectionInput("STERILITY", "无菌")));
    }
}
