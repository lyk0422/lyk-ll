package com.example.starter.model;

/**
 * 管制事件状态。
 */
public enum ControlEventStatus {
    /** 进行中（含救援流程） */
    ACTIVE,
    /** 已申请复开，等待双岗确认 */
    REOPEN_REQUESTED,
    /** 已复开，归档终态 */
    RESUMED
}
