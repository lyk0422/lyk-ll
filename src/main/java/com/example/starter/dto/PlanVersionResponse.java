package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 日前计划版本详情：版本号、是否当前版本与全部 96 段 × 机组明细（发布后不可变，可复算）。
 */
public record PlanVersionResponse(
        Long planId,
        String farmCode,
        LocalDate planDate,
        int version,
        boolean current,
        Instant publishedAt,
        List<PlanItemView> items) {

    /** 单条计划明细：时段序号（1..96）、机组编码与核定计划出力（kW）。 */
    public record PlanItemView(
            int periodIndex,
            String turbineCode,
            int plannedKw) {
    }
}
