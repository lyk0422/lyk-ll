package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 回收重量登记请求：每个未改判正品的已登记过渡卷至多登记一次。
 *
 * @param requestId 幂等键
 * @param rollNo    纸卷卷号
 * @param weightKg  可回收公斤数，单位 kg，正数且最多三位小数
 */
public record RegisterRecycleQuotaRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo,
        @NotNull BigDecimal weightKg) {
}
