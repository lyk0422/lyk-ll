package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 日结输出：实际替代率、超标时段、降掺记录三项勾稽可复算。
 *
 * @param effectiveHours          当日逐小时实际生效替代率（含降掺后比例），用于复算实际替代率
 * @param actualSubstitutionRate  当日按小时加权的实际替代率
 * @param exceededPeriods         小时均值超标时段
 * @param reductionRecords        降掺指令记录（含升级强制降掺）
 * @param sealed                  是否已封存（终态）
 */
public record DailySummaryResponse(
        String planNo,
        java.time.LocalDate summaryDate,
        BigDecimal actualSubstitutionRate,
        List<EffectiveHour> effectiveHours,
        List<ExceededPeriod> exceededPeriods,
        List<CommandResponse> reductionRecords,
        boolean sealed,
        java.time.LocalDateTime sealedAt) {

    /**
     * 单个生效小时：小时段、生效比例、是否超标。
     */
    public record EffectiveHour(
            java.time.LocalDateTime hourStart,
            BigDecimal effectiveRate,
            boolean exceeded) {
    }

    /**
     * 超标时段：小时段、超标污染物及均值（恰等于限值不列入）。
     */
    public record ExceededPeriod(
            java.time.LocalDateTime hourStart,
            String pollutants,
            BigDecimal noxAvg,
            BigDecimal so2Avg,
            BigDecimal dustAvg) {
    }
}
