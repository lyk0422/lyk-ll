package com.example.starter.ros.service;

import com.example.starter.ros.model.ParamSchema;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 候选快照校验：必填检查与依赖表达式求值。
 * 依赖表达式基于整笔更新后的候选快照一次性求值，不按字段顺序产生不同结果。
 * 表达式可用 {@code ['组名.参数名']} 索引候选 Map，例如
 * {@code ['ctrl.enabled'] == true and ['ctrl.speed'] <= ['ctrl.max_speed']}。
 */
@Component
public class CandidateSnapshotValidator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 校验候选快照，返回全部违例信息（空列表表示通过）。
     */
    public List<String> validate(List<ParamSchema> nodeSchemas, Map<String, Object> candidate) {
        List<String> violations = new ArrayList<>();
        for (ParamSchema schema : nodeSchemas) {
            String key = schema.qualifiedName();
            if (schema.required() && !candidate.containsKey(key)) {
                violations.add("必填参数缺失: " + key);
            }
        }
        StandardEvaluationContext context = new StandardEvaluationContext(candidate);
        for (ParamSchema schema : nodeSchemas) {
            String expr = schema.dependencyExpression();
            if (expr == null || expr.isBlank()) {
                continue;
            }
            String key = schema.qualifiedName();
            try {
                Expression expression = parser.parseExpression(expr);
                Boolean ok = expression.getValue(context, Boolean.class);
                if (!Boolean.TRUE.equals(ok)) {
                    violations.add("参数 " + key + " 的依赖校验未通过: " + expr);
                }
            } catch (RuntimeException ex) {
                violations.add("参数 " + key + " 的依赖表达式求值失败: " + expr + " (" + ex.getMessage() + ")");
            }
        }
        return violations;
    }
}
