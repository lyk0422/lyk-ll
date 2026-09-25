package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 编组核对记录行(对应 consist_check 表)。
 */
public record ConsistCheckRow(long id, long operationId, int consistVersion,
                              int expectedCars, BigDecimal expectedLength, BigDecimal expectedWeight,
                              int actualCars, BigDecimal actualLength, BigDecimal actualWeight,
                              boolean matched) {
}
