package com.example.starter.damage.domain;

/**
 * 残损案流转状态：到港登记 → 检验 → 估价 → 修理批复 → 复检 → 放行/全损。
 * RELEASED 与 TOTAL_LOSS 为结案终态。
 */
public enum CaseStatus {
    /** 到港登记，待检验 */
    REGISTERED,
    /** 已检验（残损项已登记），待估价 */
    SURVEYED,
    /** 已估价，待修理批复或全损结案 */
    ESTIMATED,
    /** 修理已批复，批复有效期 7 个 UTC 自然日 */
    APPROVED,
    /** 复检不合格后返修中（仅允许一次） */
    REWORK,
    /** 修理（或返修）已完工，待复检 */
    REPAIR_DONE,
    /** 复检合格，待放行结案 */
    REPAIR_PASSED,
    /** 返修后复检仍不合格，转全损评估待结案 */
    TOTAL_LOSS_PENDING,
    /** 终态：修理放行，案件已关闭 */
    RELEASED,
    /** 终态：全损结案 */
    TOTAL_LOSS
}
