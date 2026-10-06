package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 换辊实绩回报结果视图。
 *
 * @param rollNo       轧辊号
 * @param rollingKm    本次入库实绩里程（公里）；迟到实绩为 null
 * @param accepted     是否作为新实绩入库（重复回传为 false）
 * @param late         是否为日冻结后的迟到实绩（只记事件，不改已冻结里程）
 * @param firstUse     是否新辊上机后前 5 卷的首用标记
 * @param totalKm      台账累计里程（公里）
 * @param remainingKm  距周期上限的剩余里程（公里）
 * @param banned       轧辊是否已因累计里程达周期上限被禁排
 * @param recordedAt   实绩发生时刻
 */
public record RollActualView(
        String rollNo,
        BigDecimal rollingKm,
        boolean accepted,
        boolean late,
        boolean firstUse,
        BigDecimal totalKm,
        BigDecimal remainingKm,
        boolean banned,
        LocalDateTime recordedAt) {
}
