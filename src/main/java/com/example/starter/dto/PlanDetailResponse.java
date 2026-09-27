package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 日前计划版本详情视图：版本元数据与 96 时段 × 机组计划明细。
 */
public record PlanDetailResponse(
        Long planId,
        String farmCode,
        LocalDate planDate,
        int version,
        boolean current,
        Instant publishedAt,
        List<SlotView> slots) {

    /** 单时段视图：时段序号、UTC 半开区间与该时段各机组核定计划。 */
    public record SlotView(
            int slotIndex,
            Instant slotStartUtc,
            Instant slotEndUtc,
            List<TurbinePlanView> turbines) {
    }

    /** 单机组核定计划值（kW）。 */
    public record TurbinePlanView(
            String turbineCode,
            int plannedKw) {
    }
}
