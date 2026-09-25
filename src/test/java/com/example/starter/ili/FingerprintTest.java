package com.example.starter.ili;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹单元测试：规范化（排序、数值去尾零）与参数敏感性。
 */
class FingerprintTest {

    private static Dtos.DefectPayload defect(String ref, String mileage, String depth) {
        return new Dtos.DefectPayload(ref, new BigDecimal(mileage), new BigDecimal(depth));
    }

    private static Dtos.ImportAlignmentRequest request(String requestId) {
        Dtos.RunPayload run1 = new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1),
                List.of(defect("A", "100.0", "10"), defect("B", "200.0", "20")));
        Dtos.RunPayload run2 = new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1),
                List.of(defect("A", "100.3", "14"), defect("B", "200.4", "26")));
        return new Dtos.ImportAlignmentRequest(requestId, "SEG-01", run1, run2);
    }

    @Test
    void sameParametersSameFingerprintRegardlessOfDefectOrder() {
        Dtos.ImportAlignmentRequest base = request("REQ-1");
        Dtos.RunPayload run1Reordered = new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1),
                List.of(defect("B", "200.0", "20"), defect("A", "100.0", "10")));
        Dtos.ImportAlignmentRequest reordered = new Dtos.ImportAlignmentRequest("REQ-1", "SEG-01",
                run1Reordered, base.run2());
        assertEquals(Fingerprint.of(base), Fingerprint.of(reordered));
    }

    @Test
    void numericallyEqualValuesShareFingerprint() {
        Dtos.ImportAlignmentRequest base = request("REQ-1");
        Dtos.RunPayload run1Scaled = new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1),
                List.of(defect("A", "100.00", "10.0"), defect("B", "200.000", "20.00")));
        Dtos.ImportAlignmentRequest scaled = new Dtos.ImportAlignmentRequest("REQ-1", "SEG-01",
                run1Scaled, base.run2());
        assertEquals(Fingerprint.of(base), Fingerprint.of(scaled));
    }

    @Test
    void differentRequestIdChangesFingerprint() {
        assertNotEquals(Fingerprint.of(request("REQ-1")), Fingerprint.of(request("REQ-2")));
    }

    @Test
    void differentReadingChangesFingerprint() {
        Dtos.ImportAlignmentRequest base = request("REQ-1");
        Dtos.RunPayload run2Changed = new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1),
                List.of(defect("A", "100.3", "15"), defect("B", "200.4", "26")));
        Dtos.ImportAlignmentRequest changed = new Dtos.ImportAlignmentRequest("REQ-1", "SEG-01",
                base.run1(), run2Changed);
        assertNotEquals(Fingerprint.of(base), Fingerprint.of(changed));
    }

    @Test
    void fingerprintIsSha256Hex() {
        assertEquals(64, Fingerprint.of(request("REQ-1")).length());
    }
}
