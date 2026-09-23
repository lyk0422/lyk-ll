package com.example.starter.ctlog.domain;

import java.time.Instant;

/**
 * 分裂视图告警，保留双方根哈希与证据。
 */
public record SplitAlert(Long alertId, String alertType, String observerId, long treeSize,
                         String logRootHash, String observerRootHash, String evidence,
                         Instant createdAt) {
}
