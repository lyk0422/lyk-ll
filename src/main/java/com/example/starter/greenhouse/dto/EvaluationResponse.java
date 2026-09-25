package com.example.starter.greenhouse.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 日评估结果：设定达成率 + 能耗明细，封存为终态可幂等重放。
 */
public record EvaluationResponse(
        Long id,
        String zoneCode,
        LocalDate day,
        String status,
        int totalReadings,
        int achievedReadings,
        BigDecimal achievementRate,
        BigDecimal totalKwh,
        BigDecimal heatingKwh,
        BigDecimal lightingKwh,
        int ventilationCount,
        long lateReadingEvents,
        Instant sealedAt,
        List<DetailItem> details) {

    /** 复算明细行：每个有效采样点对应昼/夜设定带判定结果。 */
    public record DetailItem(
            Instant sampleTime,
            BigDecimal temperature,
            BigDecimal humidity,
            boolean daytime,
            boolean achieved) {
    }
}
