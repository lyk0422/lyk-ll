package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 回收额度调减请求：只允许减少尚未消费的额度，不得把累计消费减掉。
 *
 * @param requestId      幂等键
 * @param expectedVersion 期望额度版本号：版本 1 起，成功 +1，过期 409
 * @param newWeightKg    调减后的登记额度，单位 kg，正数最多三位小数，
 *                       须小于当前登记额度且不小于累计已消费
 */
public record AdjustRecycleQuotaRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull BigDecimal newWeightKg) {
}
