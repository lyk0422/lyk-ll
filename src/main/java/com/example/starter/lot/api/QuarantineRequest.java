package com.example.starter.lot.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 隔离设置/解除请求。
 *
 * @param action SET 设置隔离 / RELEASE 解除该批次自身来源
 * @param reason 原因（合成数据，可空）
 */
public record QuarantineRequest(
        @NotBlank String lotId,
        @NotBlank String action,
        String reason
) {
}
