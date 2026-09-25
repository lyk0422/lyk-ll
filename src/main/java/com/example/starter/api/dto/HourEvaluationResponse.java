package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 小时评估结果。
 *
 * @param planId          方案 ID
 * @param hourStart       评估自然小时起点
 * @param pollutantAverages 各污染物均值与达标判定（恰等于限值判达标）
 * @param exceeded        本小时是否存在任一污染物均值超标
 * @param consecutiveExceeded 截至本小时的连续超标小时数
 * @param banned          本次评估是否触发当日禁烧
 * @param command         超标时触发并下发的降掺指令（未超标为 null）
 */
public record HourEvaluationResponse(
        Long planId,
        LocalDateTime hourStart,
        List<PollutantAverage> pollutantAverages,
        boolean exceeded,
        int consecutiveExceeded,
        boolean banned,
        CommandResponse command) {

    /**
     * 单污染物小时均值。
     */
    public record PollutantAverage(
            String pollutant,
            BigDecimal averageMgM3,
            BigDecimal limitMgM3,
            boolean exceeded,
            int sampleCount) {
    }
}
