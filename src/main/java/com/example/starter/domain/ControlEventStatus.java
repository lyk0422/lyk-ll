package com.example.starter.domain;

/**
 * 管制事件状态；CLOSED 为归档终态。
 */
public enum ControlEventStatus {
    /** 进行中：同一线路同一时刻仅允许一个。 */
    ACTIVE,
    /** 已申请复开，等待运营与机务双岗确认。 */
    REOPEN_REQUESTED,
    /** 已归档（终态），之后到达的历史风速数据只记迟到事件。 */
    CLOSED
}
