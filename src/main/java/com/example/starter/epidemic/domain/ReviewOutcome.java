package com.example.starter.epidemic.domain;

/**
 * 审核记录结论。病种变更重起审核时，旧结论标记为 VOIDED 作废留痕。
 */
public enum ReviewOutcome {
    APPROVED,
    VOIDED,
    WITHDRAWN
}
