package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 紧急事件结束结果。
 *
 * @param emergencyId 紧急事件ID
 * @param runwayId    跑道编号
 * @param status      固定 ENDED
 * @param version     结束后的版本号（成功变更加1）
 * @param endedAt     结束时刻（UTC）
 */
public record EmergencyEndResponse(
        long emergencyId,
        String runwayId,
        String status,
        int version,
        Instant endedAt) {
}
