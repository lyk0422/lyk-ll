package com.example.starter.orchestrator.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 提交 ROS2 Launch 风格计划的请求体。
 *
 * @param planId    计划唯一标识
 * @param version   计划版本
 * @param requestId 请求唯一标识，同 requestId 同参提交幂等、异参返回 409
 * @param planName  计划展示名
 * @param nodes     节点定义列表（含参数、延迟、条件、显式依赖、健康检查与补偿声明）
 */
public record SubmitPlanRequest(
        @NotBlank String planId,
        @NotBlank String version,
        @NotBlank String requestId,
        String planName,
        @NotEmpty @Valid List<NodeSpec> nodes
) {
    /**
     * 单个节点规格。
     *
     * @param nodeId               计划内唯一节点标识
     * @param nodeName             节点名称（模拟 ROS2 节点名）
     * @param launchDelayMs        启动前延迟（毫秒），模拟 launch 延迟动作
     * @param parameters           节点参数（模拟 ROS2 parameters）
     * @param dependsOn            显式依赖的节点 id 列表
     * @param condition            启动条件谓词；为 null 表示无条件
     * @param healthCheck          健康检查声明；为 null 时启动成功即视为健康
     * @param compensation         补偿/回滚声明；为 null 表示该节点无需补偿
     */
    public record NodeSpec(
            @NotBlank String nodeId,
            String nodeName,
            Long launchDelayMs,
            boolean simulateStartFailure,
            Map<String, String> parameters,
            List<String> dependsOn,
            @Valid ConditionSpec condition,
            @Valid HealthCheckSpec healthCheck,
            @Valid CompensationSpec compensation
    ) {
    }

    /**
     * 条件谓词：只有满足条件节点才可进入已启动状态。
     * 条件引用其他节点时形成隐式依赖（depKind=CONDITION）。
     *
     * @param predicate     NODE_SUCCESS（引用节点必须成功）/ PARAM_EQUALS（引用节点参数等于期望值）
     * @param referenceNode 被引用节点 id
     * @param parameterName PARAM_EQUALS 时在引用节点参数中查找的键
     * @param expectedValue PARAM_EQUALS 期望值
     */
    public record ConditionSpec(
            @NotBlank String predicate,
            @NotBlank String referenceNode,
            String parameterName,
            String expectedValue
    ) {
    }

    /**
     * 健康检查声明。当前为同步模拟：启动成功后按声明给出检查结果。
     *
     * @param fail              true 时健康检查必失败（用于注入故障）
     * @param message           检查结果描述
     */
    public record HealthCheckSpec(
            boolean fail,
            String message
    ) {
    }

    /**
     * 补偿/回滚动作声明。
     *
     * @param action       补偿动作描述（仅记录，不执行真实进程操作）
     * @param simulateFail true 时模拟补偿失败
     */
    public record CompensationSpec(
            String action,
            boolean simulateFail
    ) {
    }
}
