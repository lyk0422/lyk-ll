package com.example.starter.rolling.api.dto;

import java.time.LocalDateTime;

/**
 * 轧辊台账日冻结结果视图。
 *
 * @param frozenRolls 本次冻结的轧辊数量（已冻结的不重复计数）
 * @param frozenAt    冻结时刻
 */
public record RollFreezeView(int frozenRolls, LocalDateTime frozenAt) {
}
