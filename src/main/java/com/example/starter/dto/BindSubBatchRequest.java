package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 子批绑定请求：将包装子批绑定到通关单并预占申报支数。
 *
 * @param requestId        幂等键
 * @param batchNo          子批批号（须已存在审查流程，且企业品种与通关单一致）
 * @param declaredQuantity 预占申报支数（正整数，累计不超通关单总数，恰占满合法）
 * @param expectedVersion  期望的通关单版本（乐观锁，不匹配拒绝）
 */
public record BindSubBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @Min(1) Integer declaredQuantity,
        @NotNull @Min(1) Integer expectedVersion
) {
}
