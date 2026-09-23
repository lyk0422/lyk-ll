package com.example.starter.domain;

/**
 * 证据生命周期状态。
 */
public enum EvidenceStatus {
    /** 可保留：正常登记或被活动保全救回的状态。 */
    RETAINED,
    /** 已标记待清除：进入清除批次但尚未最终确认。 */
    MARKED,
    /** 已清除：最终确认后仅保留摘要与清单记录，内容不再可用。 */
    PURGED
}
