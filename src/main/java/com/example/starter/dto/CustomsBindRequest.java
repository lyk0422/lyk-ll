package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 包装子批绑定通关单请求：绑定即按申报支数预占，累计不得超过通关单可核销总数。
 *
 * @param requestId        幂等键
 * @param declarationNo    合成通关单号
 * @param batchNo          包装子批号（对应一个审查流程）
 * @param declaredQuantity 本子批申报数量（正整数，单位：支）
 * @param expectedVersion  调用方持有的通关单版本，与当前版本不符整笔拒绝
 * @param occurredAt       客户端精确请求时刻（UTC，纳入幂等指纹，不替代业务时钟有效期判定）
 */
public record CustomsBindRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String batchNo,
        @Positive @NotNull Integer declaredQuantity,
        @NotNull Integer expectedVersion,
        @NotNull java.time.Instant occurredAt
) {
}
