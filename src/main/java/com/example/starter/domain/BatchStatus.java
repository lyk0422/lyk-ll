package com.example.starter.domain;

/**
 * 清除批次状态。
 */
public enum BatchStatus {
    /** 已标记：候选证据已原子写入批次，等待最终确认。 */
    MARKED,
    /** 已最终确认：不可变清除清单已冻结。 */
    PURGED
}
