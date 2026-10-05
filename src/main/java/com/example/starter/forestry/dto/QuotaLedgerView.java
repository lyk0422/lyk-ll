package com.example.starter.forestry.dto;

import java.math.BigDecimal;

/**
 * 双口径限额台账视图（按树种档位输出）。占用量由台账流水实时汇总，可复算；
 * 查询按树种编码排序，重复查询结果稳定。
 *
 * @param speciesCode     树种编码（限额档位）
 * @param quotaYear       限额年度
 * @param totalTrees      年度株数限额，单位株
 * @param totalVolume     年度蓄积限额，单位立方米
 * @param heldTrees       当前占用株数（HOLD 正、RELEASE 负的净额），单位株
 * @param heldVolume      当前占用蓄积（净额），单位立方米
 * @param remainingTrees  剩余株数额度，单位株
 * @param remainingVolume 剩余蓄积额度，单位立方米
 */
public record QuotaLedgerView(
        String speciesCode,
        Integer quotaYear,
        Integer totalTrees,
        BigDecimal totalVolume,
        Integer heldTrees,
        BigDecimal heldVolume,
        Integer remainingTrees,
        BigDecimal remainingVolume
) {
}
