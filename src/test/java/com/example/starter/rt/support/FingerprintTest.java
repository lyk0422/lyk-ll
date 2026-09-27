package com.example.starter.rt.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.rt.domain.OperationType;

/**
 * 结构化指纹单元测试：规范 JSON 编码保证无分隔符歧义。
 */
class FingerprintTest {

    @Test
    @DisplayName("同结构同指纹；Map 键序不影响指纹")
    void sameStructureSameFingerprintRegardlessOfKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("patientId", "P1");
        a.put("level", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("level", 2);
        b.put("patientId", "P1");

        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "R1", a))
                .isEqualTo(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "R1", b));
    }

    @Test
    @DisplayName("合法文本含分隔符（等号/换行/引号/冒号）仍与结构边界区分")
    void separatorCharactersInTextAreDistinguished() {
        // 值 "a=b" 与键含 "a=b" 的伪造结构必须得到不同指纹
        Map<String, Object> valueWithSeparator = new LinkedHashMap<>();
        valueWithSeparator.put("k", "a=b\nc=d");
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("k", "a");
        forged.put("b\nc", "d");

        assertThat(Fingerprint.structured(OperationType.DEVICE_UPDATE, "R2", valueWithSeparator))
                .isNotEqualTo(Fingerprint.structured(OperationType.DEVICE_UPDATE, "R2", forged));

        // 字符串 "1" 与数值 1 类型不同，指纹不同
        Map<String, Object> asString = new LinkedHashMap<>();
        asString.put("level", "1");
        Map<String, Object> asNumber = new LinkedHashMap<>();
        asNumber.put("level", 1);
        assertThat(Fingerprint.structured(OperationType.DEVICE_UPDATE, "R3", asString))
                .isNotEqualTo(Fingerprint.structured(OperationType.DEVICE_UPDATE, "R3", asNumber));
    }

    @Test
    @DisplayName("集合顺序与精确时刻参与指纹：任一不同指纹不同")
    void collectionsAndInstantsAffectFingerprint() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("items", List.of(
                Map.of("fractionNo", 1, "windowStartUtc", "2026-09-27T01:00:00Z"),
                Map.of("fractionNo", 2, "windowStartUtc", "2026-09-27T01:10:00Z")));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("items", List.of(
                Map.of("fractionNo", 2, "windowStartUtc", "2026-09-27T01:10:00Z"),
                Map.of("fractionNo", 1, "windowStartUtc", "2026-09-27T01:00:00Z")));
        Map<String, Object> otherInstant = new LinkedHashMap<>();
        otherInstant.put("items", List.of(
                Map.of("fractionNo", 1, "windowStartUtc", "2026-09-27T01:00:01Z"),
                Map.of("fractionNo", 2, "windowStartUtc", "2026-09-27T01:10:00Z")));

        String fp = Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "R4", base);
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "R4", reordered))
                .isNotEqualTo(fp);
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "R4", otherInstant))
                .isNotEqualTo(fp);
        // 操作类型与 requestId 同样参与
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_CANCEL, "R4", base))
                .isNotEqualTo(fp);
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "R5", base))
                .isNotEqualTo(fp);
    }
}
