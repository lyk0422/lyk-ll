package com.example.starter.torque;

/**
 * 工序卡状态机：派工→力矩紧固→自检互检→见证点→放行归档。
 */
public enum CardStatus {
    /** 已派工，紧固未全部完成。 */
    DISPATCHED,
    /** 全部紧固点合格，待自检。 */
    SELF_CHECK,
    /** 自检完成，待互检。 */
    MUTUAL_CHECK,
    /** 互检完成，H 点待见证（无 H 点时不经过此态）。 */
    WITNESS_PENDING,
    /** 见证缺席，转等待中。 */
    WAITING,
    /** 等待满 2 小时已升级。 */
    ESCALATED,
    /** 见证完成，可进入覆盖工序并放行归档。 */
    COVERING,
    /** 终态：已放行归档。 */
    RELEASED,
    /** 终态：同一紧固点返工 2 次后仍超差，整批隔离评审。 */
    QUARANTINED
}
