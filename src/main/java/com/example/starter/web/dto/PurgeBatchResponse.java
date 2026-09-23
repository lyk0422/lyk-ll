package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 清除批次响应：候选清单中 rescued=true 表示最终确认时被活动保全救回。
 */
public record PurgeBatchResponse(
        String batchId,
        String markRequestId,
        Instant cutoffTime,
        Long expectedVersion,
        String status,
        List<Item> items,
        Instant createdAt,
        Instant finalizedAt
) {
    /**
     * 候选条目。
     */
    public record Item(String evidenceId, boolean rescued) {
    }
}
