package com.example.starter.coldchain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 交接请求：在一个事务内关闭旧段、判定结果并打开新段。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望关闭的当前开放段版本号
 * @param receiver        接收保管方（新段保管方）
 * @param handoverAt      交接时间（Asia/Shanghai）
 * @param lastSequence    本段末个设备序号
 */
public record HandoverRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String receiver,
        @NotNull LocalDateTime handoverAt,
        @NotNull Long lastSequence
) {
}
