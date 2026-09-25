package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日偏差考核表行（daily_assessment）。
 */
public record AssessmentRow(
        String stationCode,
        LocalDate gasDate,
        String voucherNo,
        BigDecimal nominatedEnergy,
        BigDecimal actualEnergy,
        BigDecimal deviation,
        BigDecimal deviationAbs,
        BigDecimal toleranceEnergy,
        boolean exempt,
        BigDecimal penalty) {
}
