package com.example.starter.lot.model;

/**
 * 批次状态。
 */
public enum LotStatus {
    /** 可用：自身与全部祖先均无生效隔离来源。 */
    AVAILABLE,
    /** 隔离中：自身或至少一个祖先存在仍生效的隔离来源。 */
    QUARANTINED
}
