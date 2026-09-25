package com.example.starter.deicing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 队列快照，按调度优先级排序，重复查询结果稳定。
 */
public record SnapshotResponse(Instant generatedAt, List<QueueItemView> items) {
}
