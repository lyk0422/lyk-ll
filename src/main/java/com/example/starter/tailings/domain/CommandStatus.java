package com.example.starter.tailings.domain;

/**
 * 泄流指令状态：下达 → 签收 → 执行 → 回执；签收超时进入已升级。
 */
public enum CommandStatus {

    ISSUED,
    ACKED,
    EXECUTING,
    RECEIPTED,
    ESCALATED
}
