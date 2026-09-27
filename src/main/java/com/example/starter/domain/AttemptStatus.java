package com.example.starter.domain;

/**
 * 周期维保履约尝试状态。
 */
public enum AttemptStatus {
    /** 进行中：已派发，等待清单完成与双人确认 */
    PLANNED,
    /** 救援抢占挂起：人员已释放、清单保留，可续派新尝试 */
    SUSPENDED,
    /** 双人共同确认完成：已登记有效维保记录并终结义务 */
    COMPLETED,
    /** 义务被旧手工维保入口结清，残留尝试终止 */
    ABORTED
}
