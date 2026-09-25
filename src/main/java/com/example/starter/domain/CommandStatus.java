package com.example.starter.domain;

/**
 * 降掺指令链状态：触发→下发→执行确认，超时未确认自动升级。
 */
public enum CommandStatus {
    /** 小时均值超标已触发，尚未下发。 */
    TRIGGERED,
    /** 已下发，等待执行确认。 */
    ISSUED,
    /** 已执行确认，降掺到位。 */
    CONFIRMED,
    /** 下发恰满30分钟未确认，自动升级并强制降至保底比例。 */
    ESCALATED
}
