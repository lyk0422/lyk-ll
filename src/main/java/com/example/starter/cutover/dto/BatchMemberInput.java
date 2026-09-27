package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 批次成员入参：申请编号与其发布时版本。
 *
 * @param expectedVersion 调用方持有的申请版本（从 1 开始）；与库内不符则整批拒绝（422）
 */
public record BatchMemberInput(
        @NotBlank String requestId,
        @NotNull @Positive Long expectedVersion
) {
}
