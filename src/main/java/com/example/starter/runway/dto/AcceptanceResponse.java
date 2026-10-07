package com.example.starter.runway.dto;

/**
 * 验收凭据提交响应。
 *
 * @param credentialId  凭据主键
 * @param orderId       施工单ID
 * @param segmentNo     段号
 * @param segmentStatus 提交后段状态：LOCKED闭锁中/COMPLETED验收完成
 * @param orderStatus   提交后施工单状态
 * @param late          是否迟到凭据：true=施工单已结束后仅登记留痕
 * @param orderVersion  提交后施工单聚合版本
 */
public record AcceptanceResponse(
        long credentialId,
        long orderId,
        int segmentNo,
        String segmentStatus,
        String orderStatus,
        boolean late,
        int orderVersion) {
}
