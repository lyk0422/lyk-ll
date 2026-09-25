package com.example.starter.deicing.api;

import java.util.List;

/**
 * 队列快照：按出队优先级排序，重复查询结果稳定可复算。
 *
 * @param nowTs   快照生成时刻（UTC epoch 毫秒）
 * @param items   排队记录视图列表（非终态优先并按规则排序）
 */
public record SnapshotResponse(long nowTs, List<QueueItemResponse> items) {
}
