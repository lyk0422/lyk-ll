package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 年度结转结果。
 *
 * @param vesselCode 渔船编号
 * @param speciesCode 鱼种编号
 * @param sourceYear 结转来源年度（UTC）
 * @param targetYear 结转目标年度（UTC，次年）
 * @param unusedKg 来源年度未用配额（千克）
 * @param capKg 结转上限（来源年度配额 20%，千克）
 * @param carriedKg 实际结转额度（千克，min(未用, 上限)，恰 20% 合法）
 * @param replayed 是否幂等重放
 */
public record CarryoverResponse(
        String vesselCode,
        String speciesCode,
        int sourceYear,
        int targetYear,
        BigDecimal unusedKg,
        BigDecimal capKg,
        BigDecimal carriedKg,
        boolean replayed) implements ReplayAware {

    @Override
    public CarryoverResponse withReplayed(boolean replayed) {
        return new CarryoverResponse(vesselCode, speciesCode, sourceYear, targetYear,
                unusedKg, capKg, carriedKg, replayed);
    }
}
