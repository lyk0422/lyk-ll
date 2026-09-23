package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 不可变清除清单响应，最终确认首次冻结后重复确认返回同一清单。
 */
public record PurgeManifestResponse(
        String batchId,
        String status,
        Instant finalizedAt,
        List<Entry> entries
) {
    /**
     * 清单条目：仅保留证据 ID 与清除时摘要。
     */
    public record Entry(String evidenceId, String sha256, Instant purgedAt) {
    }
}
