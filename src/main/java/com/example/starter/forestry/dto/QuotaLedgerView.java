package com.example.starter.forestry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 双口径限额台账视图（按树种+年度档位输出）。
 * 持有量可由台账明细逐笔复算（HOLD 为正、RELEASE 为负），重复查询结果稳定。
 *
 * @param speciesCode 树种编码（限额档位）
 * @param quotaYear   限额年度
 * @param totalTrees  年度株数限额，单位株
 * @param totalVolume 年度蓄积限额，单位立方米
 * @param heldTrees   当前株数净占用，单位株
 * @param heldVolume  当前蓄积净占用，单位立方米
 * @param entries     台账明细（按记账顺序稳定排序）
 */
public record QuotaLedgerView(
        String speciesCode,
        Integer quotaYear,
        Integer totalTrees,
        BigDecimal totalVolume,
        Long heldTrees,
        BigDecimal heldVolume,
        List<Entry> entries
) {
    /**
     * 台账明细行。
     *
     * @param applicationId 关联申请单 ID
     * @param licenseId     关联采伐证 ID（发证占用时为空）
     * @param trees         株数：占用为正、释放为负
     * @param volume        蓄积：占用为正、释放为负，单位立方米
     * @param action        HOLD 占用 / RELEASE 释放
     * @param createdDate   记账日期（UTC）
     */
    public record Entry(
            String applicationId,
            String licenseId,
            Integer trees,
            BigDecimal volume,
            String action,
            LocalDate createdDate
    ) {
    }
}
