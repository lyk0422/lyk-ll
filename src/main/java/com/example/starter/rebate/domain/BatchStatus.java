package com.example.starter.rebate.domain;

/**
 * 批次状态。
 * DRAFT 待正式申报；DECLARED 已正式申报（冻结）；APPROVED 审核通过；
 * REFUNDED 已退库；CLOSED 结案（终态）；VOID 已撤批作废。
 */
public enum BatchStatus {
    DRAFT,
    DECLARED,
    APPROVED,
    REFUNDED,
    CLOSED,
    VOID
}
