package com.example.starter.dto;

/**
 * 队列与链路状态快照。
 *
 * @param online       模拟链路是否在线
 * @param pending      待发消息数
 * @param inFlight     等待 ACK 的消息数
 * @param total        存活消息总数
 * @param capacity     全局容量上限（条）
 * @param delivered    本进程确认完成计数
 * @param lastFlushAt  最近一次补发调度时间（ISO-8601），未发生为 null
 */
public record StatusResponse(boolean online, long pending, long inFlight, long total,
                             long capacity, long delivered, String lastFlushAt) {
}
