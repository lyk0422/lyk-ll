package com.example.starter.hazmat.domain;

/**
 * 接力段状态机。
 *
 * <p>流转：IN_PROGRESS（进行中，等待双人打卡）→ COMPLETED（换班完成）；
 * 未按时完成换班被禁行冻结时为 FROZEN（随运单终态，不再接受打卡）。
 */
public enum RelaySegmentStatus {

    /** 进行中：已设置安全接力点，等待交接班双人打卡。 */
    IN_PROGRESS,
    /** 换班完成：双方打卡齐全且接班时刻严格晚于交班时刻、早于禁行起点。 */
    COMPLETED,
    /** 禁行冻结：未在夜间禁行起点前完成换班，随运单转处置。 */
    FROZEN
}
