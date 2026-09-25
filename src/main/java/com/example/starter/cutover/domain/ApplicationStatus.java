package com.example.starter.cutover.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 割接申请状态机。
 *
 * <pre>
 * CREATED -> ANALYZED -> APPROVED -> EXECUTING -> VERIFIED -> SEALED
 *                    \\-> REJECTED       \\-> ROLLED_BACK / WINDOW_OVERDUE
 * </pre>
 */
public enum ApplicationStatus {

    /** 已申请，待影响分析。 */
    CREATED,
    /** 已完成影响分析，待审批。 */
    ANALYZED,
    /** 审批通过，窗口已占用。 */
    APPROVED,
    /** 审批拒绝（优先级抢占或窗口冲突）。 */
    REJECTED,
    /** 窗口执行中。 */
    EXECUTING,
    /** 全部步骤与关键验证点通过。 */
    VERIFIED,
    /** 封板，终态。 */
    SEALED,
    /** 已回退，终态。 */
    ROLLED_BACK,
    /** 窗口超期并强制回退，终态（回退类终态）。 */
    WINDOW_OVERDUE;

    private static final Set<ApplicationStatus> TERMINAL =
            EnumSet.of(SEALED, ROLLED_BACK, WINDOW_OVERDUE, REJECTED);

    /** 已占用窗口、对其他申请构成冲突的状态。 */
    private static final Set<ApplicationStatus> WINDOW_HOLDING =
            EnumSet.of(APPROVED, EXECUTING, VERIFIED, SEALED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isWindowHolding() {
        return WINDOW_HOLDING.contains(this);
    }
}
