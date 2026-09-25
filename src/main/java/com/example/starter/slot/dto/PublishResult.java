package com.example.starter.slot.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分配表发布结果（即发布时刻的快照视图）。
 *
 * @param season           航季标识
 * @param publishedAt      首次发布时间，重复发布保持不变
 * @param alreadyPublished 是否为重复发布（幂等成功）
 * @param allocations      快照内的全部时刻占用
 */
public record PublishResult(
        String season,
        Instant publishedAt,
        boolean alreadyPublished,
        List<AllocationView> allocations) {
}
