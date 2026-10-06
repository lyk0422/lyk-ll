package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轧辊寿命台账视图。
 *
 * @param rollNo              轧辊号
 * @param cycleLimitKm        周期上限（公里）
 * @param totalKm             台账累计里程（公里）
 * @param remainingKm         距周期上限的剩余里程（公里）
 * @param recomputedTotalKm   由换辊实绩逐条复算的累计里程（公里），用于核对台账可复算
 * @param coilsUsed           上机后已轧卷数
 * @param firstUseRemaining   首用烫辊位剩余卷数（前 5 卷），0 表示首用期已过
 * @param onMachine           是否当前在机
 * @param banned              是否已因累计里程达周期上限被禁排
 * @param frozenAt            日冻结时刻；null 表示未冻结
 */
public record RollLedgerView(
        String rollNo,
        BigDecimal cycleLimitKm,
        BigDecimal totalKm,
        BigDecimal remainingKm,
        BigDecimal recomputedTotalKm,
        int coilsUsed,
        int firstUseRemaining,
        boolean onMachine,
        boolean banned,
        LocalDateTime frozenAt) {
}
