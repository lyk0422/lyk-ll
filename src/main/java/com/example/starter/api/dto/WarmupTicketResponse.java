package com.example.starter.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 升温操作票视图（含全部阶段）。
 *
 * @param ticketNo        操作票编号
 * @param kilnId          窑编号
 * @param curveVersion    合成升温曲线版本
 * @param status          EXECUTING/COMPLETED
 * @param currentStageSeq 当前执行中阶段序号
 * @param stages          阶段列表（按序号升序）
 * @param createdAt       创建时刻
 * @param completedAt     曲线走完时刻，NULL 表示仍在执行
 */
public record WarmupTicketResponse(
        String ticketNo,
        String kilnId,
        String curveVersion,
        String status,
        int currentStageSeq,
        List<WarmupStageResponse> stages,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
