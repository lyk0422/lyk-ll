package com.example.starter.damage.api;

/** 残损案状态机：到港登记→检验→估价→修理批复→复检→放行/全损。 */
public enum CaseStatus {

    /** 已到港登记，尚未检验。 */
    REGISTERED,
    /** 已完成检验，残损清单已登记。 */
    SURVEYED,
    /** 已估价，估价结论为修理。 */
    ESTIMATED,
    /** 修理批复已生效（7 个自然日有效期内）。 */
    REPAIR_APPROVED,
    /** 修理已完工，等待复检。 */
    REPAIR_COMPLETED,
    /** 复检不合格，待返修（仅允许一次）。 */
    REWORK_REQUIRED,
    /** 复检合格待放行。 */
    REINSPECT_PASSED,
    /** 复检合格已放行，案件结案（终态）。 */
    RELEASED,
    /** 判定全损，案件结案（终态）。 */
    TOTAL_LOSS
}
