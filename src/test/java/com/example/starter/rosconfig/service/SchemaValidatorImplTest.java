package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.model.ParameterSchema;
import com.example.starter.rosconfig.model.ParamType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaValidatorImplTest {

    private final SchemaValidator validator = new SchemaValidatorImpl();

    private ParameterSchema schema(String name, ParamType type, boolean required,
                                   boolean dynamic, String defaultValue, String dependsOn) {
        return new ParameterSchema(1L, "/n", "g", name, type, required, dynamic, defaultValue, dependsOn);
    }

    @Test
    void acceptsValidCandidate() {
        List<ParameterSchema> schemas = List.of(
                schema("frame_rate", ParamType.INTEGER, true, true, "30", "frame_rate <= max_fps"),
                schema("max_fps", ParamType.INTEGER, true, true, "60", null));
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("frame_rate", 45L);
        candidate.put("max_fps", 60L);
        assertThat(validator.validate(schemas, candidate)).isNull();
    }

    @Test
    void rejectsWrongType() {
        List<ParameterSchema> schemas = List.of(
                schema("frame_rate", ParamType.INTEGER, true, true, "30", null));
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("frame_rate", "fast");
        // "fast" 无法在合并阶段规范化；此处直接给字符串模拟未知/错配类型
        String reason = validator.validate(schemas, candidate);
        assertThat(reason).contains("frame_rate");
    }

    @Test
    void rejectsUnknownParameter() {
        Map<String, Object> candidate = Map.of("ghost", 1L);
        assertThat(validator.validate(List.of(), candidate)).contains("unknown parameter: ghost");
    }

    @Test
    void rejectsMissingRequired() {
        List<ParameterSchema> schemas = List.of(
                schema("mode", ParamType.STRING, true, true, null, null),
                schema("note", ParamType.STRING, false, true, null, null));
        Map<String, Object> candidate = Map.of("note", "hi");
        assertThat(validator.validate(schemas, candidate))
                .contains("missing required parameter: mode");
    }

    @Test
    void rejectsDependencyViolation() {
        List<ParameterSchema> schemas = List.of(
                schema("frame_rate", ParamType.INTEGER, true, true, "30", "frame_rate <= max_fps"),
                schema("max_fps", ParamType.INTEGER, true, true, "60", null));
        Map<String, Object> candidate = Map.of("frame_rate", 90L, "max_fps", 60L);
        assertThat(validator.validate(schemas, candidate))
                .contains("dependency violated for parameter: frame_rate");
    }

    @Test
    void dependencyEvaluationIsFieldOrderIndependent() {
        List<ParameterSchema> schemas = List.of(
                schema("a", ParamType.INTEGER, true, true, null, "a < b"),
                schema("b", ParamType.INTEGER, true, true, null, null));
        Map<String, Object> orderA = new LinkedHashMap<>();
        orderA.put("a", 1L);
        orderA.put("b", 2L);
        Map<String, Object> orderB = new LinkedHashMap<>();
        orderB.put("b", 2L);
        orderB.put("a", 1L);
        assertThat(validator.validate(schemas, orderA)).isNull();
        assertThat(validator.validate(schemas, orderB)).isNull();

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("b", 2L);
        bad.put("a", 3L);
        assertThat(validator.validate(schemas, bad)).contains("dependency violated");
    }

    @Test
    void supportsBooleanOperatorsAndReferences() {
        List<ParameterSchema> schemas = List.of(
                schema("enabled", ParamType.BOOLEAN, true, true, null, null),
                schema("speed", ParamType.DOUBLE, true, true, null,
                        "not enabled or (speed > 0 and speed < 10)"));
        Map<String, Object> disabled = new LinkedHashMap<>();
        disabled.put("enabled", false);
        disabled.put("speed", 99.0);
        assertThat(validator.validate(schemas, disabled)).isNull();

        Map<String, Object> enabledOk = Map.of("enabled", true, "speed", 5.0);
        assertThat(validator.validate(schemas, enabledOk)).isNull();

        Map<String, Object> enabledBad = Map.of("enabled", true, "speed", 50.0);
        assertThat(validator.validate(schemas, enabledBad)).contains("dependency violated");
    }
}
