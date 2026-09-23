package com.example.starter.evidence.domain;

/**
 * 清除批次候选项的最终结果。
 */
public enum PurgeItemStatus {
    /** 已清除：最终确认时仍满足清除条件。 */
    PURGED,
    /** 已救回：确认前出现生效保全，恢复为可保留状态。 */
    RESCUED
}
