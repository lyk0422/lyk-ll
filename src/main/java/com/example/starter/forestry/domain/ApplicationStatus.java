package com.example.starter.forestry.domain;

/**
 * 采伐申请单状态机：
 * 申请时蓄积设计值与复核口径一致 -> SUBMITTED（已申请）；
 * 不一致 -> VOLUME_CORRECTION_PENDING（待补正，未受理），补正一致 -> SUBMITTED，
 * 补正后仍不一致 -> WITHDRAWN（按撤回处理，不生成证照）。
 * SUBMITTED -> REVIEW_PASSED（审核通过）/ REVIEW_REJECTED（审核拒绝）
 * REVIEW_PASSED -> LICENSED（已发证）-> HARVESTED（采伐确认）
 * HARVESTED -> RENEWAL_DONE（更新验收通过）
 * 任一业务终态后可 ARCHIVED（归档，终态）。
 */
public enum ApplicationStatus {
    /** 蓄积设计值与复核口径不一致，待一次补正；未进入受理流程 */
    VOLUME_CORRECTION_PENDING,
    SUBMITTED,
    REVIEW_PASSED,
    REVIEW_REJECTED,
    /** 补正后蓄积设计值仍与复核口径不一致，按撤回处理，不生成证照 */
    WITHDRAWN,
    LICENSED,
    HARVESTED,
    RENEWAL_DONE,
    /** 限批联动：补植仍不合格，申请失败关闭 */
    RENEWAL_FAILED,
    /** 采伐证逾期作废，申请随证件关闭 */
    LICENSE_VOID,
    ARCHIVED
}
