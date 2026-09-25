package com.example.starter.carbon.web;

import java.math.BigDecimal;

/**
 * 注销清单中单批次扣减明细。
 *
 * @param accountType 账户类型：ALLOWANCE 配额 / OFFSET 抵消信用
 * @param batchNo     批次号或信用编号
 * @param amount      自该批次扣减数量（吨CO2e）
 * @param seqNo       单笔申请内扣减顺序号
 */
public record RetirementItem(
        String accountType,
        String batchNo,
        BigDecimal amount,
        Integer seqNo) {
}
