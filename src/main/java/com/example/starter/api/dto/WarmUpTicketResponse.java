package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 升温操作票视图。
 *
 * @param ticketId           票主键
 * @param ticketNo           票编号
 * @param kilnId             窑编号
 * @param curveVersion       合成曲线版本号
 * @param status             票状态 EXECUTING/HOLDING/COMPLETED
 * @param currentStageNo     当前执行阶段序号
 * @param currentStageType   当前阶段类型 RAMP/HOLD
 * @param curveStageNo       当前阶段对应曲线阶段序号
 * @param targetTemp         当前阶段目标温度（℃）
 * @param maxDurationMinutes 当前升温段最长用时（分钟），保温段为 null
 * @param stageStartedAt     当前阶段开始时刻
 * @param holdStartedAt      进入保温段时刻，非保温中为 null
 * @param confirmedOperators 当前阶段已登记确认人
 * @param createdAt          开票时刻
 * @param completedAt        曲线走完时刻，未完成为 null
 */
public record WarmUpTicketResponse(
        Long ticketId,
        String ticketNo,
        String kilnId,
        String curveVersion,
        String status,
        int currentStageNo,
        String currentStageType,
        int curveStageNo,
        BigDecimal targetTemp,
        Long maxDurationMinutes,
        LocalDateTime stageStartedAt,
        LocalDateTime holdStartedAt,
        List<String> confirmedOperators,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
