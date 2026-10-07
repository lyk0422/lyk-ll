package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 回收额度调减请求：只允许减少尚未消费的额度，不得低于累计消费。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望额度版本号：版本 1 起，成功加 1，过期 409
 * @param newQuotaKg      调减后的新额度（含已消费部分），单位 kg，非负最多三位小数，
 *                        须小于当前额度且不小于累计消费
 */
public record ReduceRecycleQuotaRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull BigDecimal newQuotaKg) {
}
