package com.example.starter.domain;

/**
 * 降掺指令链状态：触发即下发（ISSUED）→执行确认（CONFIRMED）；
 * 下发后恰满 30 分钟未确认则自动升级（ESCALATED）并强制降掺至保底比例。
 */
public enum CommandStatus {

    /** 已触发并下发，等待执行确认 */
    ISSUED,
    /** 已在 30 分钟时限内执行确认 */
    CONFIRMED,
    /** 恰满/超过 30 分钟未确认，自动升级并强制降掺至保底比例 */
    ESCALATED
}
