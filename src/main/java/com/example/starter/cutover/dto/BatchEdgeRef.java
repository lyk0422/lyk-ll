package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 有向前置依赖边：fromRequestId 为前置（须先验证通过并封板），
 * toRequestId 为受执行门禁约束的后继。两端都必须是批次成员，不允许自环。
 */
public record BatchEdgeRef(
        @NotBlank String fromRequestId,
        @NotBlank String toRequestId
) {
}
