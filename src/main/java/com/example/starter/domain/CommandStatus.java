package com.example.starter.domain;

/**
 * 处置指令状态：PENDING 待回执，CONFIRMED 已确认，ESCALATED 回执超时已升级，COMPLETED 恢复后完成。
 */
public enum CommandStatus {
    PENDING,
    CONFIRMED,
    ESCALATED,
    COMPLETED
}
