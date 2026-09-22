package com.example.starter.domain;

/**
 * 轨迹快照：某一时刻（通常是当前态或重放至指定时刻）的完整轨迹状态。
 *
 * @param trackUid       轨迹标识（T-000001 形式，按创建顺序确定性分配）
 * @param category       目标类别
 * @param status         生命周期状态
 * @param confidence     最近观测置信度 [0,1]
 * @param lastDetectionId 最近关联检测 ID
 * @param x              最近观测位置 X（米），尚无观测时为 null
 * @param y              最近观测位置 Y（米）
 * @param z              最近观测位置 Z（米）
 * @param lastObservedAt 最近观测事件时间，epoch 毫秒（UTC）
 * @param version        乐观锁版本号，每次有效变化加一
 * @param createdAt      轨迹创建事件时间，epoch 毫秒（UTC）
 * @param updatedAt      最近有效变化事件时间，epoch 毫秒（UTC）
 */
public record TrackSnapshot(
        String trackUid,
        String category,
        TrackStatus status,
        double confidence,
        String lastDetectionId,
        Double x,
        Double y,
        Double z,
        long lastObservedAt,
        long version,
        long createdAt,
        long updatedAt) {
}
