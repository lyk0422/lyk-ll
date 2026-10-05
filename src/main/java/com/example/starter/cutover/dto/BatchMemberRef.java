package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次成员引用：申请编号 + 期望版本（发布时按 expectedVersion 校验，不符整批拒绝）。
 */
public record BatchMemberRef(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion
) {
}
