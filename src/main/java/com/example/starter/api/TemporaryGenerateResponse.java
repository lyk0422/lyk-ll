package com.example.starter.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 临时加钞计划生成结果。preHolidayWindow=false 表示当日不在任何节假日前 3 日窗口内，
 * 未做任何预测与生成；plans 为本次生成的临时计划（含被裁决让路的 YIELDED 单）。
 */
public record TemporaryGenerateResponse(
        LocalDate businessDate,
        boolean preHolidayWindow,
        int generatedCount,
        List<PlanResponse> plans,
        String message
) {
}
