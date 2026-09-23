package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 发布计划快照请求。
 */
public record SnapshotRequest(
        @NotNull Instant businessTime
) {
}
