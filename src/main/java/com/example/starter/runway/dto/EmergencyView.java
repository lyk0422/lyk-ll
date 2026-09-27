package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 紧急关闭事件视图。
 *
 * @param id        紧急事件ID
 * @param runwayId  跑道编号
 * @param reason    紧急关闭原因（合成文本）
 * @param status    ACTIVE / ENDED
 * @param version   事件版本号（从1开始）
 * @param startedAt 紧急关闭生效时刻（UTC）
 * @param endedAt   结束时刻（UTC），未结束为 null
 */
public record EmergencyView(
        long id,
        String runwayId,
        String reason,
        String status,
        int version,
        Instant startedAt,
        Instant endedAt) {
}
