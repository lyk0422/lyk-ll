package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 摘挂变更响应。
 *
 * @param jobId          作业ID
 * @param consistVersion 变更后的编组版本
 * @param carCount       变更后在列辆数
 * @param totalLength    变更后总换长
 * @param totalWeight    变更后总重，单位吨
 * @param voidedTests    同事务内作废的试验结论条数
 */
public record ConsistChangeResponse(
        long jobId,
        int consistVersion,
        int carCount,
        BigDecimal totalLength,
        BigDecimal totalWeight,
        int voidedTests) {
}
