package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 强排候选/生效序列。
 *
 * @param id                     序列ID
 * @param zoneCode               流域分区代码
 * @param tideVersion            候选所固定的潮位版本（tide_reading.id）
 * @param tideLevel              候选依据的潮位（米）
 * @param direction              切换方向 ENTER 进入强排 / EXIT 退出强排
 * @param regimeVersion          生成候选时的分区工况配置版本
 * @param expectedActiveSequenceId 生成候选时在强排序列ID（无则NULL）
 * @param state                  CANDIDATE 候选 / ACTIVE 生效 / SUPERSEDED 已终结
 * @param taskTime               序列联动UTC时刻（固定为潮位时刻）
 * @param createdAt              候选生成时刻
 */
public record StrongDrainSequence(
        Long id,
        String zoneCode,
        Long tideVersion,
        BigDecimal tideLevel,
        String direction,
        Integer regimeVersion,
        Long expectedActiveSequenceId,
        String state,
        OffsetDateTime taskTime,
        LocalDateTime createdAt
) {
    public boolean isCandidate() {
        return "CANDIDATE".equals(state);
    }

    public boolean isActive() {
        return "ACTIVE".equals(state);
    }

    public boolean isSuperseded() {
        return "SUPERSEDED".equals(state);
    }

    public boolean isEnter() {
        return "ENTER".equals(direction);
    }
}
