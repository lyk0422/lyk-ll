package com.example.starter.crane.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 事件修正留痕请求：不回写原事件，只追加一条审计记录。
 *
 * @param warningId 被修正的预警主键
 * @param reason    修正原因代码
 * @param detail    修正说明
 */
public record CorrectionRequest(
        @NotNull Long warningId,
        @NotBlank String reason,
        @NotBlank String detail) {
}
