package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 航次核销复算行：申报量、日志累计量、卸渔量、最终口径量、实际核销扣减量。
 */
public record ReconcileLine(
        String speciesCode,
        BigDecimal declaredKg,
        BigDecimal loggedKg,
        BigDecimal landedKg,
        BigDecimal finalKg,
        BigDecimal deductedKg) {
}
