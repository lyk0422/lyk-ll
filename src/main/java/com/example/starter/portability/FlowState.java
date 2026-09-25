package com.example.starter.portability;

/**
 * 携号转网流程状态。
 * AUTH_ISSUED：授权码已签发，待携出方确认；
 * CONFIRMED：携出方已确认，待生效窗口广播；
 * EFFECTIVE：已生效（终态）；
 * CANCELLED：已撤销（终态）。
 */
public enum FlowState {
    AUTH_ISSUED,
    CONFIRMED,
    EFFECTIVE,
    CANCELLED;

    /**
     * 是否为终态（已生效或已撤销）。
     */
    public boolean isTerminal() {
        return this == EFFECTIVE || this == CANCELLED;
    }
}
