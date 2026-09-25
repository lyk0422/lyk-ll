package com.example.starter.repo;

// dive_operation 行记录；所有时刻为 UTC 纪元秒，null 列用包装类型。
public record OperationRow(
        Long id,
        String operationNo,
        String applicationRequest,
        String diverA,
        String diverB,
        String groupKey,
        int depthM,
        int bottomMinutes,
        String status,
        int planVersion,
        Long exitAt,
        Long observationDueAt,
        Long chamberAt,
        Long closedAt,
        long createdAt) {
}
