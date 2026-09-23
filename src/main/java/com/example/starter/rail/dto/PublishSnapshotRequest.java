package com.example.starter.rail.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布计划快照请求。
 */
public record PublishSnapshotRequest(
        @NotBlank String snapshotId
) {
}
