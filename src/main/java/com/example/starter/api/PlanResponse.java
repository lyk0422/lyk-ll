package com.example.starter.api;

import java.util.List;

/**
 * 减压方案响应。
 *
 * @param planVersion 方案版本号，首次为 1，每次变更 +1
 * @param frozenSegmentCount 方案变更冻结的旧记录条数；首次方案为 0
 */
public record PlanResponse(
        String operationNo,
        String requestId,
        int planVersion,
        int depthM,
        int bottomMinutes,
        int ndlMinutes,
        boolean atLimit,
        boolean safetyStop,
        String status,
        List<PlanProfileStep> profile,
        int frozenSegmentCount) {
}
