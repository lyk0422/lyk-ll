package com.example.starter.forestry.domain;

/**
 * 采伐申请单状态机：
 * SUBMITTED（已申请）-> REVIEW_PASSED（审核通过）/ REVIEW_REJECTED（审核拒绝）
 * REVIEW_PASSED -> LICENSED（已发证）-> HARVESTED（采伐确认）
 * HARVESTED -> RENEWAL_DONE（更新验收通过）
 * 任一业务终态后可 ARCHIVED（归档，终态）。
 */
public enum ApplicationStatus {
    SUBMITTED,
    /** 蓄积设计值与合成蓄积表复核口径不一致，不予受理；允许一次补正 */
    CORRECTION_REQUIRED,
    /** 补正后设计值仍不一致，按撤回处理，不生成证照 */
    WITHDRAWN,
    REVIEW_PASSED,
    REVIEW_REJECTED,
    LICENSED,
    HARVESTED,
    RENEWAL_DONE,
    /** 限批联动：补植仍不合格，申请失败关闭 */
    RENEWAL_FAILED,
    /** 采伐证逾期作废，申请随证件关闭 */
    LICENSE_VOID,
    ARCHIVED
}
