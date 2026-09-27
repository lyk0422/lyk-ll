package com.example.starter.rt.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.rt.domain.OperationType;

/**
 * 结构化指纹无歧义性单元测试：分隔符注入、类型差异、集合顺序、时区等价/瞬间差异。
 */
class StructuredFingerprintTest {

    private String fp(Map<String, ?> params) {
        return StructuredFingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-1", params);
    }

    @Test
    @DisplayName("同参数同指纹；任一参数不同指纹不同")
    void sameParamsSameFingerprint() {
        Map<String, ?> a = Map.of("deviceId", "D1", "level", 2);
        Map<String, ?> b = Map.of("level", 2, "deviceId", "D1");
        assertThat(fp(a)).isEqualTo(fp(b));
        assertThat(fp(a)).isNotEqualTo(fp(Map.of("deviceId", "D1", "level", 3)));
    }

    @Test
    @DisplayName("文本含换行/等号等分隔符不能伪造字段边界：拼接碰撞被区分")
    void textWithDelimitersIsUnambiguous() {
        // 危险值1：把另一字段编码进字符串，旧的 key=value\n 拼接会与合法两字段碰撞。
        Map<String, ?> injected = Map.of("deviceId", "D1\nlevel=2", "level", 1);
        Map<String, ?> benign = Map.of("deviceId", "D1", "level", 2);
        assertThat(fp(injected)).isNotEqualTo(fp(benign));

        Map<String, ?> withEquals = Map.of("deviceId", "x=y", "level", 1);
        Map<String, ?> split = Map.of("deviceId", "x", "level", 1);
        assertThat(fp(withEquals)).isNotEqualTo(fp(split));
    }

    @Test
    @DisplayName("类型标签区分：字符串\"1\"≠整数1，null≠字符串null，布尔≠字符串")
    void typesAreDistinguished() {
        assertThat(fp(Map.of("level", 1))).isNotEqualTo(fp(Map.of("level", "1")));
        java.util.Map<String, Object> nullValue = new java.util.HashMap<>();
        nullValue.put("level", null);
        assertThat(fp(nullValue)).isNotEqualTo(fp(Map.of("level", "null")));
        assertThat(fp(Map.of("ok", true))).isNotEqualTo(fp(Map.of("ok", "true")));
    }

    @Test
    @DisplayName("集合编码含计数与顺序：不同排列、嵌套、重复元素均区分")
    void collectionsAreOrderedAndCounted() {
        Map<String, ?> ab = Map.of("items", List.of("a", "b"));
        Map<String, ?> ba = Map.of("items", List.of("b", "a"));
        Map<String, ?> abFlat = Map.of("items", "ab");
        assertThat(fp(ab)).isNotEqualTo(fp(ba));
        assertThat(fp(ab)).isNotEqualTo(fp(abFlat));

        Map<String, ?> two = Map.of("items", List.of("a", "b"));
        Map<String, ?> one = Map.of("items", List.of("a\nb"));
        assertThat(fp(two)).isNotEqualTo(fp(one));
    }

    @Test
    @DisplayName("时刻按UTC绝对瞬间：不同偏移表示同一瞬间同指纹；不同瞬间不同")
    void instantsNormalizedToUtc() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 10, 1, 2, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime shanghai = utc.withOffsetSameInstant(ZoneOffset.ofHours(8));
        assertThat(shanghai.getHour()).isEqualTo(10);
        assertThat(fp(Map.of("t", utc))).isEqualTo(fp(Map.of("t", shanghai)));

        OffsetDateTime later = utc.plusMinutes(10);
        assertThat(fp(Map.of("t", utc))).isNotEqualTo(fp(Map.of("t", later)));
    }

    @Test
    @DisplayName("requestId 与操作类型参与指纹：跨操作/跨键不冲突")
    void requestIdAndOperationParticipate() {
        Map<String, ?> params = Map.of("level", 2);
        String f1 = StructuredFingerprint.of(OperationType.SCHEDULE_PUBLISH, "R1", params);
        String f2 = StructuredFingerprint.of(OperationType.SCHEDULE_PUBLISH, "R2", params);
        String f3 = StructuredFingerprint.of(OperationType.SCHEDULE_CANCEL, "R1", params);
        assertThat(f1).isNotEqualTo(f2);
        assertThat(f1).isNotEqualTo(f3);
    }
}
