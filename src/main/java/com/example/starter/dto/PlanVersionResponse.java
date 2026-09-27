package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 风场＋UTC 日期的日前计划版本索引视图：当前版本号与全部已发布版本（保留历史）。
 */
public record PlanVersionResponse(
        String farmCode,
        String planDate,
        Integer currentVersionNo,
        List<VersionSummary> versions) {

    /** 单个已发布不可变版本摘要。 */
    public record VersionSummary(
            Long planId,
            int versionNo,
            boolean current,
            Instant publishedAt,
            int segmentCount,
            int turbineCount) {
    }
}
