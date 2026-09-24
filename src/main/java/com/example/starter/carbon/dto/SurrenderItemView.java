package com.example.starter.carbon.dto;

/**
 * 注销明细视图。
 *
 * @param sourceType ALLOWANCE 配额；OFFSET 抵消信用
 * @param batchId    被扣减批次主键
 * @param quantity   扣减量（吨CO2e）
 */
public record SurrenderItemView(
        String sourceType,
        long batchId,
        long quantity) {
}
