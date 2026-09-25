package com.example.starter.epidemic.domain;

/**
 * 审核阶段。
 */
public enum ReviewStage {
    /** 初审（初报/订正后的常规审核）。 */
    REVIEW,
    /** 终审归档。 */
    FINAL,
    /** 特殊订正双人确认。 */
    SPECIAL,
    /** 撤回留痕。 */
    WITHDRAWAL
}
