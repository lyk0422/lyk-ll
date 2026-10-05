package com.example.starter.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 节假日峰值预测结果。逐 ATM 给出预测峰值、150% 阈值与是否生成临时计划；
 * 未生成的 reason 说明原因（未超阈值/ATM 冻结/当日已存在临时计划）。
 */
public record ForecastResponse(
        LocalDate holidayDate,
        String holidayName,
        int peakFactorBp,
        LocalDate windowStart,
        LocalDate windowEnd,
        List<Item> results
) {
    public record Item(
            String atmCode,
            long predictedPeak,
            long threshold,
            boolean generated,
            Long planId,
            String planStatus,
            String reason
    ) {
    }
}
