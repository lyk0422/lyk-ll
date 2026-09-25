package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 日结封存结果：实际替代率、超标时段、降掺记录三项可勾稽复算。
 *
 * @param planId           方案 ID
 * @param settleDate       日结日期（Asia/Shanghai）
 * @param actualHeatRatio  实际替代率：执行各小时生效替代率的算术均值
 * @param evaluatedHours   纳入均值的小时数
 * @param exceedancePeriods 超标时段列表
 * @param reductionCommands 降掺指令记录
 * @param sealed           是否已封存（重复封存幂等成功）
 */
public record DailySettlementResponse(
        Long planId,
        LocalDate settleDate,
        BigDecimal actualHeatRatio,
        int evaluatedHours,
        List<ExceedancePeriod> exceedancePeriods,
        List<CommandResponse> reductionCommands,
        boolean sealed) {

    /**
     * 超标时段：小时起点 + 各污染物均值。
     */
    public record ExceedancePeriod(
            String hourStart,
            List<HourEvaluationResponse.PollutantAverage> averages) {
    }
}
