package com.example.starter.evidence.domain;

/**
 * 证据保管事件类型，事件仅追加不可变。
 */
public enum CustodyEventType {
    /** 证据登记。 */
    REGISTERED,
    /** 证据被保全单覆盖。 */
    HOLD_CREATED,
    /** 证据所属保全单解除。 */
    HOLD_RELEASED,
    /** 证据被清除批次标记。 */
    MARKED,
    /** 证据在确认前被新保全救回。 */
    RESCUED,
    /** 证据被最终确认清除。 */
    PURGED
}
