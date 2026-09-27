package com.example.rebate.web.dto;

import java.util.List;

/**
 * 逐单（批次＋报关单）冲回余额查询视图。金额单位均为分。
 *
 * @param frozenTaxCents 该报关单冻结退税额快照
 * @param occupiedCents 已批准（含已收齐）冲回累计占用额
 * @param remainingCents 剩余可冲额
 * @param quotaVersion 额度行版本（从 1 开始）
 * @param clawbacks 该报关单下全部冲回单
 */
public record ClawbackBalanceView(
        String batchNo,
        String batchStatus,
        String declarationNo,
        long frozenTaxCents,
        long occupiedCents,
        long remainingCents,
        int quotaVersion,
        List<ClawbackView> clawbacks) {
}
