package com.example.starter.pension.domain;

/**
 * 继承申领状态，{@link #COMPLETED} 为终态。
 */
public enum InheritanceStatus {

    /** 已受理，继承支付尚未出款。 */
    ACCEPTED,
    /** 继承支付完成（终态），重复完成返回幂等成功。 */
    COMPLETED
}
