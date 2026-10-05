package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 升温曲线阶段。
 *
 * @param id                 自增主键
 * @param ticketId           所属升温票
 * @param seq                阶段序号（票内从 1 递增）
 * @param curveVersion       曲线版本（随阶段落库，与票内推进原子完成）
 * @param stageType          HEATING 升温段 / HOLDING 保温段（超时自动转入）
 * @param targetTemp         目标温度，摄氏度
 * @param maxDurationMinutes 升温段最长用时，分钟，恰满未确认自动转保温
 * @param status             PENDING/ACTIVE/CONFIRMED
 * @param enteredAt          进入本阶段时刻，NULL 表示尚未进入
 * @param holdingSince       转入保温段时刻，NULL 表示未进入保温
 * @param confirmUser1       第一名确认人，NULL 表示尚无确认
 * @param confirmUser2       第二名确认人，须与第一人不同，NULL 表示复核未完成
 * @param confirmedAt        双人复核完成时刻
 * @param alarm              保温段恰满 4 小时升级告警标记
 * @param alarmedAt          告警升级时刻
 */
public record WarmupStage(
        Long id,
        long ticketId,
        int seq,
        String curveVersion,
        WarmupStageType stageType,
        BigDecimal targetTemp,
        int maxDurationMinutes,
        WarmupStageStatus status,
        LocalDateTime enteredAt,
        LocalDateTime holdingSince,
        String confirmUser1,
        String confirmUser2,
        LocalDateTime confirmedAt,
        boolean alarm,
        LocalDateTime alarmedAt) {
}
