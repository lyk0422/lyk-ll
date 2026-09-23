package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 交接请求。
 *
 * @param requestId       幂等键（货件内唯一）
 * @param expectedVersion 请求方预期的当前版本
 * @param custodian       交出方（须与当前打开段保管方一致）
 * @param receiver        接收方
 * @param handoverAt      交接时间，Unix 毫秒时间戳（UTC）
 * @param lastSequence    本段最后一个设备序号
 */
public record HandoverRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String custodian,
        @NotBlank String receiver,
        @NotNull Long handoverAt,
        @NotNull Long lastSequence
) {
}
