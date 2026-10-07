package com.example.starter.runway.dto;

/**
 * 清场凭据提交响应。
 *
 * @param credentialId      凭据主键
 * @param orderId           所属施工单ID
 * @param segmentNo         段号
 * @param late              是否迟到凭据（只读留痕，不改变状态）
 * @param segmentStatus     提交后段状态：PENDING/LOCKED/CLEARED
 * @param distinctAcceptors 该段正常验收凭据的不同验收人数
 * @param orderStatus       提交后施工单状态
 * @param orderVersion      提交后施工单聚合版本
 */
public record ClearanceResponse(
        long credentialId,
        long orderId,
        int segmentNo,
        boolean late,
        String segmentStatus,
        int distinctAcceptors,
        String orderStatus,
        int orderVersion) {
}
