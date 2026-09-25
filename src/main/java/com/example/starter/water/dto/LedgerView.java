package com.example.starter.water.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 取水户年度额度台账视图。
 *
 * @param permittedQuota      年度许可总量快照（m³）
 * @param carryoverDeduction  上一超许可年度按 1:1 结转的扣减额度（m³）
 * @param effectiveQuota      当年有效额度 = 许可总量 - 结转扣减（m³）
 * @param declaredTotal       当年各自然月最后已确认版本受理水量合计（m³）
 * @param actualTotal         按计量读数增量累计的当年实际取水量（m³）
 * @param remaining           申报剩余额度 = 有效额度 - 已确认受理合计（m³）
 * @param excess              封存结算时超许可部分（m³），未封存为 null
 * @param sealed              年度计划是否已封存（终态）
 */
public record LedgerView(
        Long userId,
        int year,
        BigDecimal permittedQuota,
        BigDecimal carryoverDeduction,
        BigDecimal effectiveQuota,
        BigDecimal declaredTotal,
        BigDecimal actualTotal,
        BigDecimal remaining,
        BigDecimal excess,
        boolean sealed,
        List<MonthLine> months) {

    /**
     * 单月对冲行。
     *
     * @param month           UTC 自然月 yyyy-MM
     * @param declaredVolume  最近一次原始申报水量（m³）
     * @param acceptedVolume  最近已确认版本受理水量（m³）
     * @param actualVolume    按表底时刻归入该月的实际取水量（m³）
     * @param version         已确认版本号；无确认计划为 null
     */
    public record MonthLine(
            String month,
            BigDecimal declaredVolume,
            BigDecimal acceptedVolume,
            BigDecimal actualVolume,
            Integer version) {
    }
}
