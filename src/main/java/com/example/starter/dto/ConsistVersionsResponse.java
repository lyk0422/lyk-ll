package com.example.starter.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 编组版本列表响应(可按版本复算辆数/换长/总重)。
 */
public record ConsistVersionsResponse(long operationId, List<VersionSummary> versions) {

    /**
     * 单个编组版本汇总。
     */
    public record VersionSummary(int consistVersion, int carCount,
                                 BigDecimal totalLength, BigDecimal totalWeight) {
    }
}
