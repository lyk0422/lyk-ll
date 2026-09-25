package com.example.starter.domain;

/**
 * 工单轨迹事件类型。
 */
public enum EventType {
    /** 接报 */
    REPORT,
    /** 研判（优先级命中） */
    ASSESS,
    /** 派单 */
    DISPATCH,
    /** 资源冲突挂单 */
    PARK,
    /** 抢修开始 */
    REPAIR_START,
    /** 单方复电确认 */
    RESTORE_CONFIRM,
    /** 双确认齐备，复电完成 */
    RESTORED,
    /** 发电车流程启动 */
    GENERATOR_START,
    /** 复电后发电车撤回 */
    GENERATOR_STOP,
    /** 被合并进新单（原单轨迹） */
    MERGE_OUT,
    /** 新单承接合并来源（新单轨迹） */
    MERGE_IN,
    /** 日结封存 */
    SEAL,
    /** 封存后迟到回报，仅记事件 */
    LATE_AFTER_SEAL,
    /** 同故障点同时段重复接报归并 */
    DEDUP_MERGE
}
