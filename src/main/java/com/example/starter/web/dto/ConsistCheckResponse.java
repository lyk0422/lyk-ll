package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 编组核对响应。
 *
 * @param checkId             核对记录ID
 * @param matched             是否与运统1一致
 * @param consistVersion      核对的编组版本
 * @param expectedCarCount    运统1辆数
 * @param actualCarCount      实际辆数
 * @param expectedTotalLength 运统1换长
 * @param actualTotalLength   实际换长
 * @param expectedTotalWeight 运统1总重，单位吨
 * @param actualTotalWeight   实际总重，单位吨
 * @param message             说明
 */
public record ConsistCheckResponse(
        long checkId,
        boolean matched,
        int consistVersion,
        int expectedCarCount,
        int actualCarCount,
        BigDecimal expectedTotalLength,
        BigDecimal actualTotalLength,
        BigDecimal expectedTotalWeight,
        BigDecimal actualTotalWeight,
        String message) {
}
