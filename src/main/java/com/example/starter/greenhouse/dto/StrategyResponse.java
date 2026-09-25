package com.example.starter.greenhouse.dto;

import java.math.BigDecimal;

/**
 * 策略视图响应。
 */
public record StrategyResponse(
        Long id,
        String strategyCode,
        String cropCode,
        String zoneCode,
        BigDecimal dayTempTarget,
        BigDecimal dayTempTolerance,
        BigDecimal dayHumidityTarget,
        BigDecimal dayHumidityTolerance,
        BigDecimal nightTempTarget,
        BigDecimal nightTempTolerance,
        BigDecimal nightHumidityTarget,
        BigDecimal nightHumidityTolerance,
        String status,
        String validationMessage) {
}
