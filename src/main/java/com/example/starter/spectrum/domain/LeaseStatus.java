package com.example.starter.spectrum.domain;

/**
 * 租约生命周期状态。
 */
public enum LeaseStatus {

    /** 活动。 */
    ACTIVE,

    /** 已取消：取消时刻起失效，历史版本保留。 */
    CANCELLED
}
