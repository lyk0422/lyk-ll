package com.example.starter.domain;

/**
 * 升温操作票状态。
 */
public enum WarmupTicketStatus {
    /** 执行中：曲线未走完，禁止投料。 */
    EXECUTING,
    /** 曲线全部阶段双人复核确认完成，允许投料。 */
    COMPLETED
}
