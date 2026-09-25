package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

import com.example.starter.hazmat.domain.RouteApprovalStatus;

/**
 * 运单路线核准记录：申请与每次紧急改线各一条；同一运单至多一条 APPROVED。
 *
 * @param id          主键
 * @param shipmentId  运单 ID
 * @param routeCode   路线编码
 * @param versionNo   路线版本号（首版 1，改线递增）
 * @param status      核准状态
 * @param rejectReason 驳回原因（状态为 REJECTED 时非空）
 * @param createdAt   创建时刻
 * @param decidedAt   核准/驳回/终止时刻
 */
public record RouteApproval(
        long id,
        long shipmentId,
        String routeCode,
        int versionNo,
        RouteApprovalStatus status,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
