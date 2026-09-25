package com.example.starter.epidemic.domain;

/**
 * 报告卡生命周期状态。
 *
 * <p>流转：初报后 {@link #PENDING_REVIEW}（待审核）→ 审核通过 {@link #PENDING_FINAL}（待终审）
 * → 终审归档 {@link #ARCHIVED}（只读终态）；审核通过前可撤回为 {@link #WITHDRAWN}（终态）。
 * 病种变更类订正会把卡片从 {@link #PENDING_FINAL} 重置回 {@link #PENDING_REVIEW}。</p>
 */
public enum CardStatus {
    /** 待初审：初报提交后、或病种变更类订正后所处状态。 */
    PENDING_REVIEW,
    /** 待终审：初审通过、尚未归档；非病种变更订正不改变该状态。 */
    PENDING_FINAL,
    /** 已归档（终审）：只读终态，仅允许双人“特殊订正”。 */
    ARCHIVED,
    /** 已撤回：审核通过前撤回，终态不可恢复。 */
    WITHDRAWN
}
