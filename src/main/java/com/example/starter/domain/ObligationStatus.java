package com.example.starter.domain;

/**
 * 维保履约义务状态。
 */
public enum ObligationStatus {
    /** 待履约：可派发尝试 */
    OPEN,
    /** 已由履约尝试完成并登记有效维保记录 */
    FULFILLED,
    /** 已由旧手工维保登记入口结清 */
    SETTLED
}
