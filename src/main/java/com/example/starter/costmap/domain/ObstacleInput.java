package com.example.starter.costmap.domain;

/**
 * 一次障碍物更新请求的规范化输入。
 *
 * @param requestId       幂等请求 ID
 * @param sourceId        来源 ID（同优先级同时间戳时按字典序决胜）
 * @param sourceType      来源类型，决定固定优先级
 * @param region          更新覆盖的矩形区域（左闭右开）
 * @param cost            上报代价 0-100，100 为致命障碍
 * @param eventTime       来源事件时间戳，Unix 毫秒
 * @param ttlMillis       存活时长，单位毫秒；{@code null} 表示不过期
 * @param expectedVersion 调用方预期的地图版本；{@code null} 不做版本冲突校验
 */
public record ObstacleInput(
        String requestId,
        String sourceId,
        SourceType sourceType,
        Region region,
        int cost,
        long eventTime,
        Long ttlMillis,
        Long expectedVersion) {
}
