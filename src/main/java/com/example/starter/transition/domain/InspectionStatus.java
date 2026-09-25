package com.example.starter.transition.domain;

/**
 * 过渡纸卷检验状态。
 */
public enum InspectionStatus {
    /** 待检（登记时初始）。 */
    PENDING,
    /** 已检（首次判定完成，强制降级）。 */
    INSPECTED,
    /** 归档后迟到：只登记迟到事件，不产生判定。 */
    LATE
}
