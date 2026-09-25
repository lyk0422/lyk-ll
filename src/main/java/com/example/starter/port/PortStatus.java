package com.example.starter.port;

/**
 * 携号转网流程状态。
 *
 * <p>流转：APPLIED（已申请）→ CODE_ISSUED（已签码）→ CONFIRMED（携出方已确认，待生效）
 * → EFFECTIVE（已生效，终态）；生效前可撤销进入 CANCELLED（终态）。
 */
public enum PortStatus {

    /** 已申请，资格校验通过，尚未签发授权码 */
    APPLIED,
    /** 授权码已签发，等待携出方确认 */
    CODE_ISSUED,
    /** 携出方已确认，等待生效窗口广播 */
    CONFIRMED,
    /** 已生效（终态） */
    EFFECTIVE,
    /** 已撤销（终态） */
    CANCELLED;

    /**
     * 判断是否为终态（已生效或已撤销）。
     */
    public boolean isTerminal() {
        return this == EFFECTIVE || this == CANCELLED;
    }
}
