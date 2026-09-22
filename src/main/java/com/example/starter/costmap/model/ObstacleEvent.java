package com.example.starter.costmap.model;

/**
 * 一次障碍物更新事件。
 *
 * @param requestId   客户端幂等请求ID
 * @param sourceId    障碍物来源ID
 * @param priority    来源优先级，数值越大越优先
 * @param region      障碍物覆盖区域
 * @param timestampMs 障碍物事件时间戳，epoch 毫秒，Asia/Shanghai 业务时区
 * @param ttlMs       存活时长，毫秒；存活条件 now &lt;= timestampMs + ttlMs
 */
public record ObstacleEvent(
        String requestId,
        String sourceId,
        int priority,
        GridRegion region,
        long timestampMs,
        long ttlMs) {

    /** 过期时刻（epoch 毫秒）。 */
    public long expiresAt() {
        return timestampMs + ttlMs;
    }
}
