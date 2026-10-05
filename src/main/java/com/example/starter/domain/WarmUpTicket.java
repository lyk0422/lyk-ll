package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 冷修后升温操作票。
 *
 * @param id             主键
 * @param requestId      开票幂等键
 * @param ticketNo       升温票编号
 * @param kilnId         窑编号
 * @param curveVersion   合成曲线版本号（开票时快照）
 * @param status         票状态
 * @param currentStageNo 当前执行阶段序号
 * @param holdStartedAt  进入保温段时刻（Asia/Shanghai），非保温中为 null
 * @param createdAt      开票时刻
 * @param completedAt    曲线走完时刻，未完成为 null
 */
public record WarmUpTicket(
        Long id,
        String requestId,
        String ticketNo,
        String kilnId,
        String curveVersion,
        WarmUpTicketStatus status,
        int currentStageNo,
        LocalDateTime holdStartedAt,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
