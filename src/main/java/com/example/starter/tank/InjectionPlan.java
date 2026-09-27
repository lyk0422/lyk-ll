package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划：预占 → 回执/取消 的全生命周期计量单。
 *
 * @param id                 主键
 * @param stationId          门站ID
 * @param tankId             储罐ID
 * @param requestId          下达计划的幂等键
 * @param source             来源 PRESSURE 压力联动 / MANUAL 人工受控加注
 * @param bizTime            业务时刻
 * @param requiredKg         预占所需量 kg 三位小数
 * @param consumedKg         已转实际消耗量 kg
 * @param releasedKg         已释放未用预占 kg
 * @param status             ISSUED 已预占下发 / EXECUTED 已回执 / CANCELLED 已取消
 * @param version            计划版本号
 * @param executedAt         回执时刻
 * @param cancelledAt        取消时刻
 */
public record InjectionPlan(
        Long id,
        Long stationId,
        Long tankId,
        String requestId,
        String source,
        LocalDateTime bizTime,
        BigDecimal requiredKg,
        BigDecimal consumedKg,
        BigDecimal releasedKg,
        String status,
        Integer version,
        LocalDateTime executedAt,
        LocalDateTime cancelledAt) {
}
