package com.example.starter.ros.service;

import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.model.ParamType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选快照校验单元测试：必填、依赖表达式，以及校验不依赖字段迭代顺序。
 */
class CandidateSnapshotValidatorTest {

    private final CandidateSnapshotValidator validator = new CandidateSnapshotValidator();

    private ParamSchema dynamic(String name, ParamType type, String expr) {
        return new ParamSchema("n", "g", name, type, null, false, true, expr);
    }

    @Test
    void requiredMissingIsReported() {
        ParamSchema required = new ParamSchema("n", "g", "topic", ParamType.STRING,
                null, true, true, null);
        List<String> violations = validator.validate(List.of(required), Map.of());
        assertThat(violations).singleElement().asString().contains("必填参数缺失", "g.topic");
    }

    @Test
    void dependencyExpressionEvaluatedAgainstFullCandidateSnapshot() {
        ParamSchema speed = dynamic("speed", ParamType.DOUBLE,
                "['g.speed'] <= ['g.max_speed']");
        ParamSchema max = dynamic("max_speed", ParamType.DOUBLE, null);

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("g.speed", 4.0);
        ok.put("g.max_speed", 5.0);
        assertThat(validator.validate(List.of(speed, max), ok)).isEmpty();

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("g.speed", 9.0);
        bad.put("g.max_speed", 5.0);
        assertThat(validator.validate(List.of(speed, max), bad))
                .singleElement().asString().contains("依赖校验未通过");
    }

    @Test
    void validationResultIsIndependentOfFieldInsertionOrder() {
        ParamSchema speed = dynamic("speed", ParamType.INTEGER,
                "['g.speed'] <= ['g.max_speed']");
        ParamSchema max = dynamic("max_speed", ParamType.INTEGER, null);
        List<ParamSchema> schemas = List.of(speed, max);

        Map<String, Object> orderA = new LinkedHashMap<>();
        orderA.put("g.speed", 100);
        orderA.put("g.max_speed", 60);

        Map<String, Object> orderB = new LinkedHashMap<>();
        orderB.put("g.max_speed", 60);
        orderB.put("g.speed", 100);

        List<String> violationsA = validator.validate(schemas, orderA);
        List<String> violationsB = validator.validate(List.of(max, speed), orderB);
        assertThat(violationsA).isEqualTo(violationsB);
        assertThat(violationsA).isNotEmpty();
    }

    @Test
    void brokenExpressionReportedAsViolationNotThrown() {
        // 候选快照中不存在的键索引为 null，对 null 调用实例方法必然在求值期失败
        ParamSchema broken = dynamic("x", ParamType.INTEGER, "['g.missing'].byteValue() == 0");
        List<String> violations = validator.validate(List.of(broken), Map.of("g.x", 1));
        assertThat(violations).singleElement().asString().contains("依赖表达式求值失败");
    }
}
