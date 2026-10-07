package com.example.starter.hazmat.domain;

/**
 * 人员资格预约状态机。
 *
 * <p>流转：ACTIVE（占用中）→ RELEASED（首次换班打卡前撤销，或运单终结释放）；
 * 交接班双人打卡完成时 → CONSUMED（换班核销，占用结束）。
 */
public enum ReservationStatus {

    /** 占用中：班段已被该接班员占用，同员重叠班段拒绝新预约。 */
    ACTIVE,
    /** 已释放：预约撤销或运单终结释放，不再占用班段。 */
    RELEASED,
    /** 已核销：受控段换班完成，预约随交接班生效结案。 */
    CONSUMED
}
