package com.example.starter.evidence.domain;

/**
 * 清除批次状态。
 */
public enum PurgeBatchStatus {
    /** 已标记：候选清单已原子写入，等待最终确认。 */
    MARKED,
    /** 已确认：清除清单已冻结，不可变。 */
    CONFIRMED
}
