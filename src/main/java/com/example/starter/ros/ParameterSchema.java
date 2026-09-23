package com.example.starter.ros;

/**
 * 参数 Schema 定义。
 *
 * @param nodeId         所属节点 ID
 * @param groupName      参数组名（模拟 ROS 参数命名空间/分组）
 * @param key            参数名
 * @param type           参数标量类型
 * @param defaultValue   默认值（按类型存储的文本表示，空串表示无默认值）
 * @param required       是否必填；候选快照中必填参数不得为空
 * @param dynamic        是否为动态参数；{@code false} 表示静态参数，仅能通过待重启配置修改
 * @param dependencyExpr 依赖表达式，基于整笔更新后的候选快照求值，为空表示无依赖
 */
public record ParameterSchema(
        String nodeId,
        String groupName,
        String key,
        ParamType type,
        String defaultValue,
        boolean required,
        boolean dynamic,
        String dependencyExpr) {
}
