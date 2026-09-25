package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 故障接报响应。
 *
 * @param reportId    接报记录ID
 * @param workOrderId 关联工单ID
 * @param status      工单当前状态
 * @param merged      是否按“故障点+时段”归并到既有接报
 * @param replayed    是否为同键同参幂等重放
 * @param reportedAt  接报时间
 */
public record FaultReportResponse(
        long reportId,
        long workOrderId,
        String status,
        boolean merged,
        boolean replayed,
        LocalDateTime reportedAt) {
}
