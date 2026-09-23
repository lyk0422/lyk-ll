package com.example.starter.ros;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选快照校验器单元测试：必填、依赖冲突、静态参数识别、字段顺序无关。
 */
class CandidateValidatorTest {

    private final CandidateValidator validator = new CandidateValidator();

    private final List<ParameterSchema> camera = List.of(
            schema("frame_rate", ParamType.INT, "30", true, true, "frame_rate > 0 && frame_rate <= 120"),
            schema("resolution_width", ParamType.INT, "640", true, true, "resolution_width > 0"),
            schema("resolution_height", ParamType.INT, "480", true, true,
                    "resolution_height > 0 && resolution_height <= resolution_width"),
            schema("device_serial", ParamType.STRING, "SIM-1", true, false, null));

    @Test
    void validCandidateHasNoErrors() {
        Map<String, Object> candidate = new LinkedHashMap<>(Map.of(
                "frame_rate", 90L, "resolution_width", 1920L, "resolution_height", 1080L,
                "device_serial", "SIM-1"));
        assertTrue(validator.validate("camera_node", camera, candidate).isEmpty());
    }

    @Test
    void dependencyConflictReportedWithExpression() {
        Map<String, Object> candidate = Map.of(
                "frame_rate", 200L, "resolution_width", 640L, "resolution_height", 480L,
                "device_serial", "SIM-1");
        List<String> errors = validator.validate("camera_node", camera, candidate);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("frame_rate"));
    }

    @Test
    void crossParameterDependencyIsOrderIndependent() {
        // 两个键同时更新为合法组合；无论变更集遍历顺序如何，表达式看到的都是同一完整快照
        Map<String, Object> valid = Map.of(
                "frame_rate", 30L, "resolution_width", 320L, "resolution_height", 240L,
                "device_serial", "SIM-1");
        assertTrue(validator.validate("camera_node", camera, valid).isEmpty());

        Map<String, Object> invalid = Map.of(
                "frame_rate", 30L, "resolution_width", 240L, "resolution_height", 320L,
                "device_serial", "SIM-1");
        List<String> errors = validator.validate("camera_node", camera, invalid);
        assertTrue(errors.stream().anyMatch(e -> e.contains("resolution_height")));
    }

    @Test
    void requiredAbsenceReported() {
        List<ParameterSchema> schemas = List.of(
                schema("mandatory", ParamType.STRING, null, true, true, null),
                schema("optional", ParamType.INT, "1", false, true, null));
        List<String> errors = validator.validate("n", schemas, Map.of("optional", 1L));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("mandatory"));
    }

    @Test
    void detectsChangedStaticParameter() {
        Map<String, Object> current = Map.of(
                "frame_rate", 30L, "resolution_width", 640L, "resolution_height", 480L,
                "device_serial", "SIM-1");
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("frame_rate", 60L);
        changes.put("device_serial", "SIM-2");
        Map<String, Object> candidate = Map.of(
                "frame_rate", 60L, "resolution_width", 640L, "resolution_height", 480L,
                "device_serial", "SIM-2");
        List<String> changedStatic =
                validator.changedStaticKeys(camera, current, changes, candidate);
        assertEquals(List.of("device_serial"), changedStatic);
    }

    @Test
    void unchangedStaticValueIsNotAChange() {
        Map<String, Object> current = Map.of(
                "frame_rate", 30L, "resolution_width", 640L, "resolution_height", 480L,
                "device_serial", "SIM-1");
        Map<String, Object> changes = Map.of("device_serial", "SIM-1");
        assertTrue(validator.changedStaticKeys(camera, current, changes, current).isEmpty());
    }

    private static ParameterSchema schema(String key, ParamType type, String defaultValue,
                                          boolean required, boolean dynamic, String expr) {
        return new ParameterSchema("camera_node", "g", key, type, defaultValue,
                required, dynamic, expr);
    }
}
