package com.example.starter.dto;

import java.time.LocalDate;

/**
 * 日结输出视图：各回路当日调光次数与能耗。
 *
 * @param circuitId 回路主键
 * @param code      回路编码
 * @param name      回路名称
 * @param date      结算日期(UTC)
 * @param dimCount  当日调光次数
 * @param energyMwh 当日能耗，单位毫瓦时(mWh)，由调光留痕复算
 * @param energyWh  当日能耗，单位瓦时(Wh)
 */
public record SettlementView(
        long circuitId,
        String code,
        String name,
        LocalDate date,
        int dimCount,
        long energyMwh,
        double energyWh) {
}
