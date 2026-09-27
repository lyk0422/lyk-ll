package com.example.starter.rt.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.rt.domain.OperationType;

/**
 * 幂等指纹结构化编码单元测试：合法文本含分隔符也须区分，集合与精确时刻无歧义。
 */
class FingerprintTest {

    @Test
    @DisplayName("值含分隔符（=、换行）的合法文本与拆分后的两字段指纹不同")
    void separatorInValueIsDistinguished() {
        Map<String, Object> single = Map.of("a", "1\nb=2");
        Map<String, Object> split = Map.of("a", "1", "b", "2");

        String f1 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-1", single);
        String f2 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-1", split);

        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    @DisplayName("键含等号与值含逗号的组合不会与另一组合混淆")
    void separatorInKeyAndValueIsDistinguished() {
        Map<String, Object> first = new TreeMap<>(Map.of("k", "a,b"));
        Map<String, Object> second = new TreeMap<>(Map.of("k", "a", "k,b", ""));

        String f1 = Fingerprint.of(OperationType.TREATMENT, "REQ-2", first);
        String f2 = Fingerprint.of(OperationType.TREATMENT, "REQ-2", second);

        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    @DisplayName("集合元素含分隔符：单元素 \"x, y\" 与两元素 [x, y] 指纹不同")
    void listElementsWithSeparatorAreDistinguished() {
        Map<String, Object> oneElement = Map.of("fractions", List.of("x, y"));
        Map<String, Object> twoElements = Map.of("fractions", List.of("x", "y"));

        String f1 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-3", oneElement);
        String f2 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-3", twoElements);

        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    @DisplayName("参数顺序不同指纹相同；任一字段值不同指纹不同")
    void orderIndependentButValueSensitive() {
        Map<String, Object> first = new TreeMap<>();
        first.put("patientId", "P1");
        first.put("level", 2);
        first.put("fractions", List.of(1, 2));
        Map<String, Object> reordered = new TreeMap<>();
        reordered.put("fractions", List.of(1, 2));
        reordered.put("level", 2);
        reordered.put("patientId", "P1");
        Map<String, Object> different = new TreeMap<>(first);
        different.put("level", 3);

        assertThat(Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-4", first))
                .isEqualTo(Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-4", reordered));
        assertThat(Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-4", first))
                .isNotEqualTo(Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-4", different));
    }

    @Test
    @DisplayName("同一精确时刻的不同偏移表示指纹相同；不同时刻指纹不同")
    void instantsNormalizedToUtc() {
        OffsetDateTime plusEight = OffsetDateTime.of(2026, 9, 28, 18, 0, 0, 0,
                ZoneOffset.ofHours(8));
        OffsetDateTime utc = OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime other = OffsetDateTime.of(2026, 9, 28, 10, 0, 1, 0, ZoneOffset.UTC);

        String f1 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-5",
                Map.of("windowStartUtc", plusEight));
        String f2 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-5",
                Map.of("windowStartUtc", utc));
        String f3 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-5",
                Map.of("windowStartUtc", other));

        assertThat(f1).isEqualTo(f2);
        assertThat(f1).isNotEqualTo(f3);
    }

    @Test
    @DisplayName("嵌套集合（分次窗口列表）逐元素参与指纹，任一元素不同指纹不同")
    void nestedCollectionParticipates() {
        OffsetDateTime start = OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.UTC);
        Map<String, Object> fraction1 = Map.of("fractionNo", 1,
                "windowStartUtc", start, "windowEndUtc", start.plusMinutes(10));
        Map<String, Object> fraction2 = Map.of("fractionNo", 2,
                "windowStartUtc", start.plusMinutes(10), "windowEndUtc", start.plusMinutes(20));
        Map<String, Object> fraction2Shifted = Map.of("fractionNo", 2,
                "windowStartUtc", start.plusMinutes(11), "windowEndUtc", start.plusMinutes(21));

        String f1 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-6",
                Map.of("fractions", List.of(fraction1, fraction2)));
        String f2 = Fingerprint.of(OperationType.SCHEDULE_PUBLISH, "REQ-6",
                Map.of("fractions", List.of(fraction1, fraction2Shifted)));

        assertThat(f1).isNotEqualTo(f2);
    }
}
