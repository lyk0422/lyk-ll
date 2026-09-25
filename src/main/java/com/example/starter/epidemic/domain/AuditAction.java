package com.example.starter.epidemic.domain;

/**
 * 审核轨迹动作类型，覆盖提交、审核、撤回、归档及特殊订正全过程，保证轨迹可复算。
 */
public enum AuditAction {
    /** 初报提交。 */
    SUBMIT,
    /** 初审通过。 */
    REVIEW_APPROVED,
    /** 病种变更类订正导致原初审结论作废。 */
    REVIEW_VOIDED,
    /** 审核通过前撤回。 */
    WITHDRAWN,
    /** 终审归档。 */
    ARCHIVED,
    /** 归档后发起特殊订正。 */
    SPECIAL_STARTED,
    /** 特殊订正第一名审核员确认。 */
    SPECIAL_FIRST_CONFIRMED,
    /** 特殊订正第二名审核员确认并生效。 */
    SPECIAL_SECOND_CONFIRMED,
    /** 订正提交（非病种变更）。 */
    CORRECTED
}
