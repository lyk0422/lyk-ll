package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 分鱼种数量行（响应）。
 */
public record WeightLineView(String speciesCode, BigDecimal weightKg) {
}
