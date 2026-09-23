package com.example.starter.rosconfig.model;

/**
 * 参数 Schema 定义，按节点 + 参数组组织。
 *
 * @param id           主键
 * @param node         节点名（ROS 2 Node 名）
 * @param group        参数组名
 * @param name         参数名（节点内唯一）
 * @param type         参数标量类型
 * @param required     是否必填；为 true 且默认值/已提交值/变更集都未提供时校验失败
 * @param dynamic      是否为动态参数；false 表示静态参数，热更新禁止修改
 * @param defaultValue 默认值的 JSON 字面量，null 表示无默认值
 * @param dependsOn    依赖表达式，基于整笔更新后的候选快照求值，必须返回布尔真；null 表示无约束
 */
public record ParameterSchema(
        Long id,
        String node,
        String group,
        String name,
        ParamType type,
        boolean required,
        boolean dynamic,
        String defaultValue,
        String dependsOn
) {
}
