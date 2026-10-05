package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 升温操作票。
 *
 * @param id              自增主键
 * @param requestId       创建幂等键
 * @param ticketNo        操作票编号（业务唯一）
 * @param kilnId          窑编号
 * @param curveVersion    执行的合成升温曲线版本
 * @param status          票状态（EXECUTING/COMPLETED）
 * @param currentStageSeq 当前执行中阶段序号，阶段推进与曲线版本记录原子更新
 * @param createdAt       创建时刻（Asia/Shanghai）
 * @param completedAt     曲线走完时刻，NULL 表示仍在执行
 */
public record WarmupTicket(
        Long id,
        String requestId,
        String ticketNo,
        String kilnId,
        String curveVersion,
        WarmupTicketStatus status,
        int currentStageSeq,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
