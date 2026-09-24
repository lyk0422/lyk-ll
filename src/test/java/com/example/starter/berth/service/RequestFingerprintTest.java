package com.example.starter.berth.service;

import com.example.starter.berth.dto.CreateWindowRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹单元测试：验证字段拼接无歧义且覆盖全部业务字段。
 */
class RequestFingerprintTest {

    @Test
    void fieldsWithAmbiguousConcatenation_produceDifferentFingerprints() {
        // "ab"+"c" 与 "a"+"bc" 直接拼接相同，加分隔符后必须不同
        String first = RequestFingerprint.ofFields("ab", "c");
        String second = RequestFingerprint.ofFields("a", "bc");

        assertNotEquals(first, second);
    }

    @Test
    void sameFields_produceSameFingerprint() {
        assertEquals(RequestFingerprint.ofFields("x", "y", "z"),
                RequestFingerprint.ofFields("x", "y", "z"));
    }

    @Test
    void fingerprint_coversAllBusinessFields() {
        CreateWindowRequest base = request("REQ-1", "船A", "BERTH-A",
                "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, null);

        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-2", "船A", "BERTH-A",
                        "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船B", "BERTH-A",
                        "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船A", "BERTH-B",
                        "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船A", "BERTH-A",
                        "2026-10-15T09:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船A", "BERTH-A",
                        "2026-10-15T08:00:00Z", "2026-10-15T11:00:00Z", "5.00", 2, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船A", "BERTH-A",
                        "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "6.00", 2, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船A", "BERTH-A",
                        "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 3, null)));
        assertNotEquals(RequestFingerprint.of(base),
                RequestFingerprint.of(request("REQ-1", "船A", "BERTH-A",
                        "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, 7L)));
    }

    @Test
    void numericallyEqualDrafts_produceSameFingerprint() {
        CreateWindowRequest a = request("REQ-1", "船A", "BERTH-A",
                "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.00", 2, null);
        CreateWindowRequest b = request("REQ-1", "船A", "BERTH-A",
                "2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z", "5.0", 2, null);

        assertEquals(RequestFingerprint.of(a), RequestFingerprint.of(b));
    }

    private CreateWindowRequest request(String requestId, String vessel, String berth,
                                        String start, String end, String draft, int pilots, Long supersedesId) {
        return new CreateWindowRequest(requestId, vessel, berth,
                Instant.parse(start), Instant.parse(end), new BigDecimal(draft), pilots, supersedesId);
    }
}
