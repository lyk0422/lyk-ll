package com.example.starter.cutover.dto;

import com.example.starter.cutover.StepType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 割接步骤定义（申请时提交，按 seqNo 顺序执行）。
 *
 * @param name 步骤名称
 * @param type 步骤类型 NORMAL/CRITICAL/HARDCHECK
 */
public record StepDefinition(
        @NotBlank String name,
        @NotNull StepType type
) {
}
