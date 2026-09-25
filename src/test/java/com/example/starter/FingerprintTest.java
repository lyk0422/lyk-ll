package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.starter.support.Fingerprints;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹纯单元测试：指纹覆盖 requestId 与全部业务字段，任一字段变化指纹必变。
 */
class FingerprintTest {

    @Test
    void sameInputs_sameFingerprint() {
        List<String> a = List.of("req-1", "F101", "点A", "50");
        List<String> b = List.of("req-1", "F101", "点A", "50");
        assertEquals(Fingerprints.sha256(a), Fingerprints.sha256(b));
    }

    @Test
    void differentBusinessField_differentFingerprint() {
        String base = Fingerprints.sha256(List.of("req-1", "F101", "点A", "50"));
        assertNotEquals(base, Fingerprints.sha256(List.of("req-1", "F101", "点A", "51")),
                "影响户数变化必须产生不同指纹");
        assertNotEquals(base, Fingerprints.sha256(List.of("req-1", "F102", "点A", "50")),
                "馈线变化必须产生不同指纹");
        assertNotEquals(base, Fingerprints.sha256(List.of("req-1", "F101", "点B", "50")),
                "故障点变化必须产生不同指纹");
        assertNotEquals(base, Fingerprints.sha256(List.of("req-2", "F101", "点A", "50")),
                "requestId 变化必须产生不同指纹");
    }

    @Test
    void nullAndEmptyCanonicalized() {
        assertEquals(Fingerprints.sha256(java.util.Arrays.asList("a", null)),
                Fingerprints.sha256(java.util.Arrays.asList("a", "")));
    }
}
