package com.example.starter.gas.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日偏差考核明细视图：可逐日复算，结果稳定。
 */
public record AssessmentResponse(
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
