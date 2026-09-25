package com.example.starter.epidemic.domain;

/**
 * 报告卡生命周期状态。
 */
public enum CardStatus {

    /** 初报/订正后待审核。 */
    PENDING_REVIEW,
    /** 审核通过，可订正、可终审归档。 */
    APPROVED,
    /** 审核通过前撤回，终态不可恢复。 */
    WITHDRAWN,
    /** 终审归档，终态只读；仅双人特殊订正可追加版本。 */
    ARCHIVED
}
