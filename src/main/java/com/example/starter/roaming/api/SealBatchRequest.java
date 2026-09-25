package com.example.starter.roaming.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 清分日封存请求：按 UTC 日冻结当日已结束会话并生成清分单。
 *
 * @param requestId     业务幂等键
 * @param settlementDay 清分日（UTC）
 */
public record SealBatchRequest(
        @NotBlank String requestId,
        @NotNull LocalDate settlementDay) {
}
