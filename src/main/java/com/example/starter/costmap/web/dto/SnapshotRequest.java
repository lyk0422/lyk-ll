package com.example.starter.costmap.web.dto;

import jakarta.validation.constraints.Size;

/**
 * 发布快照请求。
 *
 * @param snapshotId 可选快照 ID；缺省由服务端生成
 * @param label      可选备注
 */
public record SnapshotRequest(
        @Size(max = 128) String snapshotId,
        @Size(max = 255) String label) {
}
