package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.model.ParameterSchema;

import java.util.Map;

/**
 * 基于整笔更新后候选快照的 Schema 校验器：类型、必填与依赖表达式。
 */
public interface SchemaValidator {

    /**
     * 校验候选快照，返回第一个违规原因；全部通过返回 null。
     *
     * @param schemas   该节点全部参数 Schema
     * @param candidate 合并默认值、已提交值与变更集后的完整候选快照（值已按类型规范化）
     */
    String validate(java.util.List<ParameterSchema> schemas, Map<String, Object> candidate);
}
