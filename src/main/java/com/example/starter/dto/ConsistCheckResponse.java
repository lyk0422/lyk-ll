package com.example.starter.dto;

import java.math.BigDecimal;

/**
 * 编组核对响应: matched=false 时发车确认将被拒绝。
 */
public record ConsistCheckResponse(long operationId, int consistVersion, boolean matched,
                                   int expectedCars, BigDecimal expectedLength, BigDecimal expectedWeight,
                                   int actualCars, BigDecimal actualLength, BigDecimal actualWeight) {
}
