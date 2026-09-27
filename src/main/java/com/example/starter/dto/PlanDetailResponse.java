package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 指定日前计划版本详情：不可变版本头及其 96 段 × 机组核定计划，用于版本查询与证据复核。
 */
public record PlanDetailResponse(
        Long planId,
        String farmCode,
        String planDate,
        int versionNo,
        boolean current,
        Instant publishedAt,
        List<SegmentView> segments) {

    /** 单个半开时段及其各机组核定计划。 */
    public record SegmentView(
            int segmentIndex,
            List<EntryView> entries) {
    }

    /** 单机组该段核定计划。 */
    public record EntryView(
            String turbineCode,
            int plannedPowerKw) {
    }
}
