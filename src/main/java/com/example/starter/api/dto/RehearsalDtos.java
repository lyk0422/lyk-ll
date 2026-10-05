package com.example.starter.api.dto;

import com.example.starter.repo.RehearsalRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 策略下发前预演留档相关响应。
 */
public final class RehearsalDtos {

    private RehearsalDtos() {
    }

    /**
     * 预演留档：合成能耗模型输入、估算结果与仲裁结论，随策略版本保存，可按同版本模型复算。
     * 能耗单位 kWh、速率单位 kWh/h；budget/consumed/remaining 为空表示当日未分配预算。
     */
    public record RehearsalResponse(
            String strategyCode,
            String requestId,
            String modelVersion,
            Instant effectiveTime,
            LocalDate evalDate,
            BigDecimal dayTemp,
            BigDecimal nightTemp,
            BigDecimal humiditySetpoint,
            long durationMinutes,
            BigDecimal hourlyRateKwh,
            BigDecimal estimatedKwh,
            BigDecimal budgetKwh,
            BigDecimal consumedKwh,
            BigDecimal remainingKwh,
            String handlingConflict,
            String verdict,
            Instant createdAt) {

        public static RehearsalResponse from(RehearsalRepository.Rehearsal r) {
            return new RehearsalResponse(r.strategyCode(), r.requestId(), r.modelVersion(),
                    r.effectiveTime(), r.evalDate(), r.dayTemp(), r.nightTemp(), r.humiditySetpoint(),
                    r.durationMinutes(), r.hourlyRateKwh(), r.estimatedKwh(), r.budgetKwh(),
                    r.consumedKwh(), r.remainingKwh(), r.handlingConflict(), r.verdict(), r.createdAt());
        }
    }
}
