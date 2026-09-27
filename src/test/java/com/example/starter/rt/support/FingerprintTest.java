package com.example.starter.rt.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.rt.domain.OperationType;

/**
 * 结构化指纹单元测试：合法文本含分隔符也须区分，参数顺序无关、集合顺序有关。
 */
class FingerprintTest {

    @Test
    @DisplayName("含分隔符的合法文本不会与其他参数组合撞指纹")
    void separatorInTextStillDistinct() {
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("note", "x\nversion=1");
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("note", "x");
        split.put("version", 1);

        String f1 = Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-1", combined);
        String f2 = Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-1", split);
        assertThat(f1).isNotEqualTo(f2);

        Map<String, Object> withEquals = Map.of("deviceId", "DEV=1\nlevel=2");
        Map<String, Object> asTwoFields = Map.of("deviceId", "DEV=1", "level", 2);
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-2", withEquals))
                .isNotEqualTo(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-2", asTwoFields));
    }

    @Test
    @DisplayName("参数键顺序不影响指纹，集合元素顺序影响指纹")
    void keyOrderInsensitiveListOrderSensitive() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("patientId", "P1");
        a.put("level", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("level", 2);
        b.put("patientId", "P1");
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-3", a))
                .isEqualTo(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-3", b));

        Map<String, Object> list12 = Map.of("items", List.of(
                Map.of("fractionNo", 1), Map.of("fractionNo", 2)));
        Map<String, Object> list21 = Map.of("items", List.of(
                Map.of("fractionNo", 2), Map.of("fractionNo", 1)));
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-4", list12))
                .isNotEqualTo(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-4", list21));
    }

    @Test
    @DisplayName("requestId 或操作类型不同则指纹不同")
    void operationAndRequestIdMatter() {
        Map<String, Object> params = Map.of("patientId", "P1");
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-5", params))
                .isNotEqualTo(Fingerprint.structured(OperationType.SCHEDULE_CANCEL, "REQ-5", params));
        assertThat(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-5", params))
                .isNotEqualTo(Fingerprint.structured(OperationType.SCHEDULE_PUBLISH, "REQ-6", params));
    }
}
