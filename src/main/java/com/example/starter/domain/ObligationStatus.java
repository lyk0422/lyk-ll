package com.example.starter.domain;

/**
 * 维保履约义务状态。
 */
public enum ObligationStatus {
    /** 未结清：可派发尝试 */
    OPEN,
    /** 已结清：本周期有效维保已登记（含手工登记结清） */
    FULFILLED
}
