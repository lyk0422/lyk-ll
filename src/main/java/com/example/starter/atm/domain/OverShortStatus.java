package com.example.starter.atm.domain;

/**
 * 长短款挂账状态：
 * OPEN 未销账（须在发生日起 3 个自然日内销账，UTC 日界，恰第 3 日受理合法）
 * SETTLED 已按期销账
 * ESCALATED 逾期升级（冻结责任人新押运任务）
 */
public enum OverShortStatus {
    OPEN,
    SETTLED,
    ESCALATED
}
