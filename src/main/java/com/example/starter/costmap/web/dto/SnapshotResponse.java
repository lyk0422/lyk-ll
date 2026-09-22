package com.example.starter.costmap.web.dto;

/**
 * 快照元数据。
 */
public record SnapshotResponse(String snapshotId, String versionId, long seq) {
}
