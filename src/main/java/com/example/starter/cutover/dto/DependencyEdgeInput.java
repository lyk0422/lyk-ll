package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 有向前置依赖入参：{@code fromRequestId -> toRequestId}。
 * toRequestId 开始执行前，fromRequestId 必须已验证通过并封板。
 * 两端必须都是批内成员；自环（from==to）整批拒绝。
 */
public record DependencyEdgeInput(
        @NotBlank String fromRequestId,
        @NotBlank String toRequestId
) {
}
