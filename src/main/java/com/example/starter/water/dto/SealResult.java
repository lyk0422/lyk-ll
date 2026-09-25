package com.example.starter.water.dto;

import java.math.BigDecimal;

/**
 * 年度计划封存结果；封存为终态，重复封存幂等返回同一结果。
 *
 * @param actualTotal         当年实际取水量（m³）
 * @param permittedQuota      年度许可总量快照（m³）
 * @param carryoverDeduction  当年被上年结转扣减的额度（m³）
 * @param effectiveQuota      当年有效额度（m³）
 * @param excess              超许可年度总量部分（m³），按 1:1 结转次年
 * @param carryoverToNextYear 写入次年额度的扣减量（m³）
 */
public record SealResult(
        Long userId,
        int year,
        BigDecimal actualTotal,
        BigDecimal permittedQuota,
        BigDecimal carryoverDeduction,
        BigDecimal effectiveQuota,
        BigDecimal excess,
        BigDecimal carryoverToNextYear,
        boolean sealed) {
}
