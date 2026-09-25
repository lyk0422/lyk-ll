package com.example.starter.domain;

/**
 * 故障工单状态，流转：REPORTED→ASSESSED→(WAITING)→DISPATCHED→REPAIRING→RESTORED→SEALED；
 * MERGED 与 SEALED 为终态。
 */
public enum OrderStatus {
    /** 已接报，待研判 */
    REPORTED,
    /** 已研判，进入派单队列 */
    ASSESSED,
    /** 资源冲突挂单等待 */
    WAITING,
    /** 已派单 */
    DISPATCHED,
    /** 抢修中 */
    REPAIRING,
    /** 已复电（调度与现场双确认齐备） */
    RESTORED,
    /** 日结封存（终态） */
    SEALED,
    /** 已合并，引用新单（终态） */
    MERGED
}
