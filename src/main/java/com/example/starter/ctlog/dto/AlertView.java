package com.example.starter.ctlog.dto;

/**
 * 单条分裂视图告警视图。
 */
public record AlertView(long alertId, String alertType, String observerId, long treeSize,
                        String logRootHash, String observerRootHash, String evidence,
                        String createdAt) {
}
