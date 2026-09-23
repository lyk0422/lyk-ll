package com.example.starter.lot.domain;

/**
 * 批次状态。
 */
public enum LotStatus {

    /** 可用：自身及全部祖先均无生效隔离来源。 */
    AVAILABLE,

    /** 隔离：自身或至少一个祖先存在仍生效的隔离来源。 */
    QUARANTINED
}
