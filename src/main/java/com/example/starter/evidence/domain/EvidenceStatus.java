package com.example.starter.evidence.domain;

/**
 * 证据生命周期状态。
 */
public enum EvidenceStatus {
    /** 可保留：未处于任何未确认清除批次。 */
    ACTIVE,
    /** 已标记：进入未确认的清除批次，仍可被新保全救回。 */
    MARKED,
    /** 已清除：最终确认完成，不可再被保全。 */
    PURGED
}
