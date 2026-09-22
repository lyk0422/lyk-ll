package com.example.starter.domain;

/**
 * 关联证据：解释某次关联/切换/重识别的判定依据。
 *
 * @param trackUid        所属轨迹
 * @param evidenceType    证据类型
 * @param linkedTrackUid  关联的另一条轨迹（如交叉对象、切换后的新身份），可为空
 * @param detectionId     触发证据的检测 ID
 * @param eventTime       事件时间，epoch 毫秒（UTC）
 * @param version         证据写入时轨迹版本号
 * @param distance        检测位置与轨迹最近观测位置的欧氏距离（米）
 * @param gateThreshold   当时采用的关联门限（米）
 * @param detail          人类可读的判定说明
 */
public record AssociationEvidence(
        Long id,
        String trackUid,
        EvidenceType evidenceType,
        String linkedTrackUid,
        String detectionId,
        long eventTime,
        long version,
        Double distance,
        Double gateThreshold,
        String detail,
        long createdAt) {
}
