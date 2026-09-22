package com.example.starter.costmap.web.dto;

/**
 * 从 H2 中保存的快照恢复查询视图的结果。
 */
public record RestoreViewResponse(String viewId, String snapshotId, String versionId, long seq) {
}
