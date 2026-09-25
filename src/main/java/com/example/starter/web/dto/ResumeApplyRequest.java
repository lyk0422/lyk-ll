package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 复工评估申请（幂等）。红色预警后速率须连续 24 小时严格低于安全线，
 * applyTime 恰满安全窗口起算点 24 小时允许申请。
 */
public record ResumeApplyRequest(
        @NotBlank String requestId,
        @NotBlank String chainNo,
        @NotNull LocalDateTime applyTime) {
}
