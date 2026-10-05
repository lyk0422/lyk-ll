package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 结转台账视图（次年台账单列）。
 *
 * @param vesselCode 渔船编号
 * @param speciesCode 鱼种编号
 * @param quotaYear 结转目标年度（UTC，次年）
 * @param sourceYear 结转来源年度（UTC）
 * @param carriedKg 结转额度（千克）
 * @param usedKg 次年已优先核销的结转额度（千克）
 * @param remainingKg 剩余可优先核销的结转额度（千克）
 */
public record CarryoverView(
        String vesselCode,
        String speciesCode,
        int quotaYear,
        int sourceYear,
        BigDecimal carriedKg,
        BigDecimal usedKg,
        BigDecimal remainingKg) {
}
