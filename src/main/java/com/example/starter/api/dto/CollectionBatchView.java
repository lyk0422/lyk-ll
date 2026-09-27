package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 采集上传批次视图。
 *
 * @param packetCount 本批实际追加的包数（不含同内容重放跳过）
 */
public record CollectionBatchView(
        String requestId,
        int packetCount,
        Instant createdAt) {
}
