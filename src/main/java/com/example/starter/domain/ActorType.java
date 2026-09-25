package com.example.starter.domain;

/**
 * 复电确认/事件主体。
 */
public enum ActorType {
    /** 调度 */
    DISPATCHER,
    /** 现场（抢修班组） */
    FIELD,
    /** 系统（自动判定） */
    SYSTEM,
    /** 报修人 */
    CALLER
}
