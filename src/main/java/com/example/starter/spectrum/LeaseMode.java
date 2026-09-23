package com.example.starter.spectrum;

/**
 * 租约占用模式。
 */
public enum LeaseMode {

    /** 独占：与任意相遇租约冲突。 */
    EXCLUSIVE,

    /** 共享：可与其他共享租约相遇，但片段内生效 EIRP 总和受上限约束。 */
    SHARED
}
