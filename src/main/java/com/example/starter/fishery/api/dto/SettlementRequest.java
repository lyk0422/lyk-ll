package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 配额核销结案请求；核销与余额扣减、结案在同一事务内完成。
 *
 * @param requestId 幂等键
 * @param tripCode 航次业务编号
 */
public record SettlementRequest(
        @NotBlank String requestId,
        @NotBlank String tripCode) {
}
