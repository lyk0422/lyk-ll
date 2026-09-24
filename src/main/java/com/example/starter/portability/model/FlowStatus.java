package com.example.starter.portability.model;

/**
 * 携号转网流程状态。
 * ACTIVATED 与 CANCELLED 为终态；进入终态后在途唯一键 active_key 置空。
 */
public enum FlowStatus {

    /** 已申请（资格校验通过，待签发授权码）。 */
    APPLIED,
    /** 已签发授权码（待携出方确认）。 */
    CODE_ISSUED,
    /** 携出方已确认（待生效窗口广播）。 */
    DONOR_CONFIRMED,
    /** 已生效（终态），授权码立即作废。 */
    ACTIVATED,
    /** 已撤销（终态）。 */
    CANCELLED;

    /**
     * 判断是否为终态。
     */
    public boolean isTerminal() {
        return this == ACTIVATED || this == CANCELLED;
    }
}
