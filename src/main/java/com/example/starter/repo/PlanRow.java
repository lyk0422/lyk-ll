package com.example.starter.repo;

// dive_plan 行记录：active=TRUE 为当前方案版本。
public record PlanRow(
        Long id,
        long operationId,
        int version,
        int depthM,
        int bottomMinutes,
        int ndlMinutes,
        boolean atLimit,
        String profileJson,
        boolean active,
        long createdAt) {
}
