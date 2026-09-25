package com.example.starter.domain;

/**
 * 救援单状态：接报→派单→到场→施救→复检恢复→回访结案。
 */
public enum RescueStatus {
    /** 已接报 */
    REPORTED,
    /** 已派单 */
    DISPATCHED,
    /** 已到场 */
    ARRIVED,
    /** 施救完成 */
    RESCUED,
    /** 复检合格并恢复运行 */
    RESTORED,
    /** 回访结案（终态） */
    CLOSED
}
