package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 升温曲线阶段视图。
 *
 * @param seq                阶段序号
 * @param stageType          HEATING 升温段 / HOLDING 保温段
 * @param targetTemp         目标温度，摄氏度
 * @param maxDurationMinutes 最长用时，分钟
 * @param status             PENDING/ACTIVE/CONFIRMED
 * @param enteredAt          进入本阶段时刻
 * @param holdingSince       转入保温段时刻，NULL 表示未进入保温
 * @param confirmUser1       第一名确认人，NULL 表示尚无确认
 * @param confirmUser2       第二名确认人，NULL 表示复核未完成
 * @param confirmedAt        双人复核完成时刻
 * @param alarm              保温段超时 4 小时告警标记
 * @param alarmedAt          告警升级时刻
 */
public record WarmupStageResponse(
        int seq,
        String stageType,
        BigDecimal targetTemp,
        int maxDurationMinutes,
        String status,
        LocalDateTime enteredAt,
        LocalDateTime holdingSince,
        String confirmUser1,
        String confirmUser2,
        LocalDateTime confirmedAt,
        boolean alarm,
        LocalDateTime alarmedAt) {
}
