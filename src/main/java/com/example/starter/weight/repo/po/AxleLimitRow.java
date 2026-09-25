package com.example.starter.weight.repo.po;

import java.math.BigDecimal;

/**
 * 轴型限值行。
 *
 * @param axleType 轴型编码
 * @param limitKg  车货总重限值（千克）
 */
public record AxleLimitRow(String axleType, BigDecimal limitKg) {
}
