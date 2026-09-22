package com.example.starter.domain;

/**
 * 轨迹事件流中的一条不可变事件。所有字段对应 track_event 表列。
 *
 * @param id             自增主键（落库前为 null），重放时作为同时刻事件的确定性次序
 * @param eventTime      业务事件时间，epoch 毫秒（UTC）
 * @param trackUid       所属轨迹
 * @param eventType      事件类型（TRACK_CREATED/OBSERVATION/STATUS_TRANSITION/ID_SWITCH 等）
 * @param fromStatus     迁移前状态，创建事件为 null
 * @param toStatus       迁移后状态
 * @param version        事件写入后轨迹版本号
 * @param detectionId    关联检测 ID，状态迁移事件可为空
 * @param source         检测来源
 * @param confidence     事件后置信度
 * @param positionX      事件后位置 X（米）
 * @param positionY      事件后位置 Y（米）
 * @param positionZ      事件后位置 Z（米）
 * @param evidenceType   关联证据类型
 * @param linkedTrackUid 关联另一条轨迹
 * @param distance       关联距离（米）
 * @param gateThreshold  采用的门限（米）
 * @param detail         说明
 * @param payload        附加 JSON
 * @param recordedAt     落库时间，epoch 毫秒（UTC）
 */
public record TrackEvent(
        Long id,
        long eventTime,
        String trackUid,
        String category,
        String eventType,
        TrackStatus fromStatus,
        TrackStatus toStatus,
        long version,
        String detectionId,
        String source,
        Double confidence,
        Double positionX,
        Double positionY,
        Double positionZ,
        EvidenceType evidenceType,
        String linkedTrackUid,
        Double distance,
        Double gateThreshold,
        String detail,
        String payload,
        long recordedAt) {
}
