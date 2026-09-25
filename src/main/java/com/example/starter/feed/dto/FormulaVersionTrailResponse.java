package com.example.starter.feed.dto;

import java.time.Instant;
import java.util.List;

/**
 * 配方版本轨迹视图：同一配方编码下各版本独立校验、冻结，可复算。
 *
 * @param formulaCode 配方编码
 * @param versions    各版本摘要（按版本号升序）
 */
public record FormulaVersionTrailResponse(
        String formulaCode,
        List<VersionView> versions
) {
    /**
     * 单个版本摘要。
     */
    public record VersionView(
            int version,
            String name,
            String targetSpecies,
            boolean containsDrug,
            String status,
            String rejectReason,
            Instant approvedAtUtc,
            Instant firstProducedAtUtc,
            Instant createdAtUtc
    ) {
    }
}
