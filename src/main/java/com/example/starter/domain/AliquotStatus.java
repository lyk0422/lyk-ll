package com.example.starter.domain;

/**
 * 分装管（aliquot）状态。
 */
public enum AliquotStatus {
    /**
     * 正常：可被检测申请占用或污染隔离。
     */
    AVAILABLE,
    /**
     * 隔离：发生污染事件，未开始申请被整批取消，已消耗量保留。
     */
    QUARANTINED
}
