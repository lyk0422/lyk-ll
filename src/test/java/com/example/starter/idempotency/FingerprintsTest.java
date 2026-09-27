package com.example.starter.idempotency;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReviseRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.IssueRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    @Test
    void customsFingerprintsVaryWithVersionAndExactMoment() {
        Instant validUntil = Instant.parse("2026-12-31T23:59:59Z");
        CustomsRegisterRequest base = new CustomsRegisterRequest(
                "req-c1", "CIQ-1", "企业", "品种", validUntil, 100);
        assertEquals(Fingerprints.customsRegister(base), Fingerprints.customsRegister(base));
        assertNotEquals(Fingerprints.customsRegister(base),
                Fingerprints.customsRegister(new CustomsRegisterRequest(
                        "req-c1", "CIQ-1", "企业", "品种", validUntil.plusSeconds(1), 100)),
                "精确时刻变化必须导致指纹不同");
        assertNotEquals(Fingerprints.customsBind("CIQ-1", new CustomsBindRequest("req-c2", 1, "B1", 10)),
                Fingerprints.customsBind("CIQ-1", new CustomsBindRequest("req-c2", 2, "B1", 10)),
                "期望版本变化必须导致指纹不同");
        assertNotEquals(Fingerprints.customsRevise("CIQ-1", new CustomsReviseRequest("req-c3", 1, 200)),
                Fingerprints.customsRevise("CIQ-1", new CustomsReviseRequest("req-c3", 1, 300)));
    }

    @Test
    void structuredFingerprintDistinguishesTextsContainingSeparators() {
        // 朴素分隔符拼接下两者编码相同：a|b|c 与 a、b|c 的字段错位组合。
        assertNotEquals(
                Fingerprints.structured("OP", "a", "b|c"),
                Fingerprints.structured("OP", "a|b", "c"),
                "合法文本含分隔符时指纹仍须区分");
        assertNotEquals(
                Fingerprints.structured("OP", "x", "10:xy;"),
                Fingerprints.structured("OP", "x", "10:xy"),
                "文本含长度前缀样式内容也须区分");
        assertEquals(Fingerprints.structured("OP", "a", "b"), Fingerprints.structured("OP", "a", "b"));
    }
}
