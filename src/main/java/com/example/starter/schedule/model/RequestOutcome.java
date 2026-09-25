package com.example.starter.schedule.model;

/**
 * 编排申请首次处理结果（幂等记录用）。
 */
public enum RequestOutcome {

    /** 已编排（可能伴随对其他条目的抢占）。 */
    SCHEDULED,

    /** 同优先级后到，冲突拒绝。 */
    REJECTED
}
