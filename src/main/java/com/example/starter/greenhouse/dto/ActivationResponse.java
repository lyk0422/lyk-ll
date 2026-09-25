package com.example.starter.greenhouse.dto;

import java.time.Instant;

/**
 * 策略下发结果。同键同参重放时按幂等语义原样返回首次结果。
 */
public record ActivationResponse(
        String strategyCode,
        String zoneCode,
        String previousStrategyCode,
        Instant effectiveAt) {
}
