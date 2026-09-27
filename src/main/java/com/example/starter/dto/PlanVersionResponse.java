package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 日前计划版本详情（不可变留证）：版本头信息与整版 96 段 × 机组明细。
 */
public record PlanVersionResponse(
        Long versionId,
        String farmCode,
        java.time.LocalDate planDate,
        int versionNo,
        boolean current,
        Instant publishedAt,
        String requestId,
        List<SegmentView> segments) {

    /** 单条核定计划：时段序号、半开区间起止（UTC）、机组与核定出力。 */
    public record SegmentView(
            int segmentIndex,
            Instant segmentStart,
            Instant segmentEnd,
            String turbineCode,
            int powerKw) {
    }
}
