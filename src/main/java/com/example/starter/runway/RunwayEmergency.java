package com.example.starter.runway;

import java.time.Instant;

/**
 * 跑道紧急关闭事件行：对应 runway_emergency 表。
 *
 * @param id         紧急事件主键
 * @param runwayId   跑道编号
 * @param reason     紧急关闭原因（合成文本）
 * @param status     ACTIVE / ENDED
 * @param activeFlag 进行中标记：1=ACTIVE，null=ENDED
 * @param startedAt  紧急关闭生效时刻（UTC）
 * @param endedAt    结束时刻（UTC），未结束为 null
 * @param version    乐观版本号，从1开始
 * @param requestId  接报幂等请求标识
 * @param createdAt  创建时间（UTC）
 * @param updatedAt  更新时间（UTC）
 */
public record RunwayEmergency(
        long id,
        String runwayId,
        String reason,
        EmergencyStatus status,
        Integer activeFlag,
        Instant startedAt,
        Instant endedAt,
        int version,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
