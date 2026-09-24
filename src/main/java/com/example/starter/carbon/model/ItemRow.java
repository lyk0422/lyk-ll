package com.example.starter.carbon.model;

/**
 * 注销明细行。
 *
 * @param sourceType ALLOWANCE 配额；OFFSET 抵消信用
 */
public record ItemRow(
        String requestId,
        String sourceType,
        long batchId,
        long quantity) {
}
