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

    @Test
    void structuredFingerprintDistinguishesTextsContainingSeparators() {
        // 合法文本含分隔符时仍须区分字段边界：拼接式编码会把两者混为一谈
        assertNotEquals(
                Fingerprints.structured("OP", "a|b", "c"),
                Fingerprints.structured("OP", "a", "b|c"),
                "含竖线的文本不得与字段边界混淆");
        assertNotEquals(
                Fingerprints.structured("OP", "x,y", "z"),
                Fingerprints.structured("OP", "x", "y,z"),
                "含逗号的文本不得与集合分隔混淆");
        assertNotEquals(
                Fingerprints.structured("OP", "k=1", "2"),
                Fingerprints.structured("OP", "k", "1=2"),
                "含等号的文本不得与键值分隔混淆");
    }

    @Test
    void structuredFingerprintDistinguishesNullEmptyAndTypes() {
        assertNotEquals(
                Fingerprints.structured("OP", (Object) null),
                Fingerprints.structured("OP", ""),
                "null 与空串必须区分");
        assertNotEquals(
                Fingerprints.structured("OP", "1"),
                Fingerprints.structured("OP", 1),
                "字符串 1 与数字 1 必须区分");
        assertNotEquals(
                Fingerprints.structured("OP", List.of("a", "b")),
                Fingerprints.structured("OP", List.of("ab")),
                "集合元素边界必须区分");
    }

    @Test
    void structuredFingerprintIsStableForSameParameters() {
        java.time.Instant moment = java.time.Instant.parse("2026-12-31T00:00:00Z");
        assertEquals(
                Fingerprints.structured("OP", "req-1", "CUS-1", moment, 100, 1),
                Fingerprints.structured("OP", "req-1", "CUS-1", moment, 100, 1),
                "同参指纹必须稳定一致");
        assertNotEquals(
                Fingerprints.structured("OP", "req-1", "CUS-1", moment, 100, 1),
                Fingerprints.structured("OP", "req-1", "CUS-1", moment, 100, 2),
                "版本号变化必须导致指纹不同");
        assertNotEquals(
                Fingerprints.structured("OP", "req-1", "CUS-1", moment, 100, 1),
                Fingerprints.structured("OP", "req-1", "CUS-1",
                        moment.plusSeconds(1), 100, 1),
                "精确时刻变化必须导致指纹不同");
    }
}
