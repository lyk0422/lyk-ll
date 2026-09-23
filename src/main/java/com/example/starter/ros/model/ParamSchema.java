package com.example.starter.ros.model;

/**
 * 单个 ROS 参数的 Schema 定义。
 *
 * @param node                 所属节点名
 * @param group                参数组名
 * @param name                 参数名（节点+组内唯一）
 * @param type                 标量类型
 * @param defaultValue         默认值（字符串字面量，读取时按 type 解析）；null 表示无默认值
 * @param required             是否必填；候选快照中必填项不得缺失
 * @param dynamic              true=动态参数，可热更新；false=静态参数，只能待重启生效
 * @param dependencyExpression 依赖表达式（SpEL），基于整笔更新后的候选快照求值，结果必须为 true；可为空
 */
public record ParamSchema(
        String node,
        String group,
        String name,
        ParamType type,
        String defaultValue,
        boolean required,
        boolean dynamic,
        String dependencyExpression
) {
    /** 全局限定键，用于跨组依赖表达式上下文。 */
    public String qualifiedName() {
        return group + "." + name;
    }
}
