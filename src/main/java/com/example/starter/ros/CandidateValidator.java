package com.example.starter.ros;

import com.example.starter.ros.support.DependencyExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 候选快照校验器：必填与依赖表达式。
 * 依赖表达式基于整笔更新后的候选快照统一求值，与字段顺序无关。
 */
@Component
public class CandidateValidator {

    /**
     * 校验单个节点的候选快照。
     *
     * @param schemas   节点全部 Schema
     * @param candidate 整笔更新后的候选完整快照
     * @return 校验错误信息列表；空列表表示通过
     */
    public List<String> validate(String nodeId,
                                 List<ParameterSchema> schemas,
                                 Map<String, Object> candidate) {
        List<String> errors = new ArrayList<>();
        for (ParameterSchema schema : schemas) {
            if (schema.required() && !candidate.containsKey(schema.key())) {
                errors.add("required parameter '" + schema.key() + "' is absent on node '" + nodeId + "'");
            }
        }
        // 依赖统一在完整候选快照上求值，先收集全部表达式再逐条求值，
        // 任何字段都不会因为先/后写入而看到中间态。
        for (ParameterSchema schema : schemas) {
            String expr = schema.dependencyExpr();
            if (expr == null || expr.isBlank()) {
                continue;
            }
            try {
                boolean satisfied = DependencyExpression.compile(expr).evaluate(candidate);
                if (!satisfied) {
                    errors.add("dependency violated for parameter '" + schema.key()
                            + "' on node '" + nodeId + "': " + expr);
                }
            } catch (IllegalArgumentException ex) {
                errors.add("invalid dependency for parameter '" + schema.key()
                        + "' on node '" + nodeId + "': " + ex.getMessage());
            }
        }
        return errors;
    }

    /**
     * 返回候选快照中实际发生变化的静态参数名：
     * 仅当变更集包含静态参数、且整笔更新后的候选值与当前生效值（已提交值或默认值）不同时才算“改变”。
     *
     * @param candidate 整笔更新后的候选快照（重置为 null 时回退默认值后的结果）
     */
    public List<String> changedStaticKeys(List<ParameterSchema> schemas,
                                          Map<String, Object> currentEffective,
                                          Map<String, Object> changes,
                                          Map<String, Object> candidate) {
        List<String> changed = new ArrayList<>();
        for (ParameterSchema schema : schemas) {
            if (schema.dynamic() || !changes.containsKey(schema.key())) {
                continue;
            }
            Object newValue = candidate.get(schema.key());
            Object oldValue = currentEffective.get(schema.key());
            if (!valuesEqual(oldValue, newValue)) {
                changed.add(schema.key());
            }
        }
        return changed;
    }

    private boolean valuesEqual(Object oldValue, Object newValue) {
        if (oldValue == null || newValue == null) {
            return oldValue == null && newValue == null;
        }
        if (oldValue instanceof Number on && newValue instanceof Number nn) {
            return Double.compare(on.doubleValue(), nn.doubleValue()) == 0;
        }
        return oldValue.equals(newValue);
    }
}
