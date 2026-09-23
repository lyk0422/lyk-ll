package com.example.starter.domain;

/**
 * 不可变保管事件类型，仅追加不修改。
 */
public enum CustodyEventType {
    /** 登记。 */
    REGISTERED,
    /** 被清除批次标记。 */
    MARKED,
    /** 最终确认时被活动保全覆盖而原子救回。 */
    RESCUED,
    /** 被法律保全覆盖。 */
    HELD,
    /** 所在保全解除。 */
    RELEASED,
    /** 最终清除。 */
    PURGED
}
