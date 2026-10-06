package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轧辊寿命台账视图。
 *
 * @param rollNo          轧辊号
 * @param status          状态：IN_SERVICE 在役 / BANNED 禁排（累计里程达周期上限）
 * @param totalKm         累计实绩里程（公里）
 * @param cycleLimitKm    周期上限（公里）
 * @param remainingKm     剩余里程（公里）= 周期上限 - 累计里程，到期为 0 或负数
 * @param coilsSinceMount 本次上机后已轧卷数（前 5 卷为首用烫辊位）
 * @param mountedAt       当前上机时刻
 * @param frozenAt        日冻结时刻；null 表示未冻结
 */
public record RollView(
        String rollNo,
        String status,
        BigDecimal totalKm,
        BigDecimal cycleLimitKm,
        BigDecimal remainingKm,
        Integer coilsSinceMount,
        LocalDateTime mountedAt,
        LocalDateTime frozenAt) {
}
