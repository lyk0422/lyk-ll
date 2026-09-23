package com.example.starter.rights;

/**
 * 排期时段状态。
 */
public enum ScheduleItemStatus {
    /** 已绑定明确授权版本。 */
    BOUND,
    /** 授权撤销时该时段尚未开始，被阻断不可播。 */
    BLOCKED
}
