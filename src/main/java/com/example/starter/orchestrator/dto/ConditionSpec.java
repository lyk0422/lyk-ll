package com.example.starter.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 条件谓词：仅当被引用节点达到期望状态时才允许本节点启动。
 * 引用关系构成隐式依赖边，参与循环检测。
 *
 * @param ref 被引用节点标识
 * @param expect 期望的被引用节点状态，如 HEALTHY
 */
public record ConditionSpec(
        @NotBlank String ref,
        @NotBlank String expect) {
}
