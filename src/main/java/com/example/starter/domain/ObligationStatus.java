package com.example.starter.domain;

/**
 * 周期维保履约义务状态。
 */
public enum ObligationStatus {
    /** 未完成：可派发尝试，或被旧手工维保入口结清 */
    OPEN,
    /** 已终结：已登记一条有效维保记录（尝试双人确认）或手工入口结清 */
    FULFILLED
}
