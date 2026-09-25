package com.example.starter.metro.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 暂记请求：出闸缺失的行程按最远站票价暂记。
 *
 * @param requestId 幂等键
 * @param txnNo     交易流水号
 */
public record EstimateRequest(
        @NotBlank String requestId,
        @NotBlank String txnNo) {
}
