package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预警决策结果。
 *
 * @param pumpdownId 预降事件ID；未触发时为 null
 * @param zoneCode   流域分区代码
 * @param alertLevel 预警级别代码
 * @param triggered  是否启动预降（橙色及以上为 true）
 * @param status     ACTIVE 预降中 / NOT_TRIGGERED 未达触发级别
 * @param startedAt  预降开始时刻
 * @param targetLevel 分区预降目标水位（米），未触发时为 null
 * @param replayed   是否为同键重放返回
 */
public record WarningResult(
        Long pumpdownId,
        String zoneCode,
        String alertLevel,
        boolean triggered,
        String status,
        LocalDateTime startedAt,
        BigDecimal targetLevel,
        boolean replayed
) {
}
