package com.example.starter.hazmat.domain;

/**
 * 夜间接力段状态机。
 *
 * <p>流转：IN_PROGRESS（已设置安全接力点，等待双方打卡）→ COMPLETED（交班/接班均打卡，换班生效）。
 * 未在夜间禁行起点前完成换班 → FROZEN（随运单禁行冻结终态一并冻结，不再接受打卡）。
 */
public enum RelayStatus {

    /** 进行中：等待交班与接班押运员双人打卡。 */
    IN_PROGRESS,
    /** 换班完成：双方打卡齐全，接班押运员接续。 */
    COMPLETED,
    /** 已冻结：恰满禁行起点仍未完成换班，随运单转处置。 */
    FROZEN
}
