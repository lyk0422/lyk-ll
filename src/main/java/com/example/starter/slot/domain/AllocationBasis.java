package com.example.starter.slot.domain;

/**
 * 时刻占用依据。
 */
public enum AllocationBasis {

    /** 历史执行率达标（&ge;80%）自动保留的时刻。 */
    RETAINED,

    /** 执行率未达标后进入公共池、经协调分配的时刻。 */
    POOL
}
