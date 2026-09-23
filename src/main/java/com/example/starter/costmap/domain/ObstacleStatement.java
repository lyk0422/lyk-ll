package com.example.starter.costmap.domain;

/**
 * 一条动态障碍物声明（某来源在某个矩形区域、某个观测时刻的占据/清除主张）。
 *
 * <p>{@code seq} 为地图内单调递增的应用序号，同时作为栅格获胜声明的稳定标识；
 * 同一 {@code sourceId + obstacleId} 的不同上报为不同 seq 的不同版本行。</p>
 *
 * @param seq        地图内单调递增应用序号
 * @param requestId  客户端幂等请求 ID
 * @param sourceId   来源 ID（传感器/上报方标识）
 * @param obstacleId 来源内障碍物身份标识
 * @param priority   来源公开优先级，数值越大优先级越高
 * @param kind       声明类型
 * @param region     声明矩形区域（含边界）
 * @param eventTime  来源观测时间戳（epoch 毫秒）
 * @param ttlMillis  存活时长（毫秒）
 * @param expireAt   过期时刻（eventTime + ttlMillis，epoch 毫秒）
 * @param createdAt  服务端接收时刻（epoch 毫秒）
 */
public record ObstacleStatement(
        long seq,
        String requestId,
        String sourceId,
        String obstacleId,
        int priority,
        StatementKind kind,
        CellRegion region,
        long eventTime,
        long ttlMillis,
        long expireAt,
        long createdAt) {
}
