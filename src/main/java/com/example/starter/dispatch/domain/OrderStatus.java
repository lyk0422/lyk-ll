package com.example.starter.dispatch.domain;

/**
 * 调度令状态机。
 * <ul>
 *   <li>ISSUED：已下达，等待闸站签收；</li>
 *   <li>ACCEPTED：已签收，逐门执行中（执行进度以回执行体现）；</li>
 *   <li>COMPLETED：全部闸门回执收齐（终态，含个别闸门偏差告警的情形）；</li>
 *   <li>TIMEOUT：超过执行时限未收齐回执，超时升级（终态）；</li>
 *   <li>CANCELLED：签收前撤销（终态）；</li>
 *   <li>ABORTED：签收后人工中止（终态）。</li>
 * </ul>
 */
public enum OrderStatus {
    ISSUED,
    ACCEPTED,
    COMPLETED,
    TIMEOUT,
    CANCELLED,
    ABORTED;

    /** 是否终态：终态令占用的闸门释放，状态不可再变。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == TIMEOUT || this == CANCELLED || this == ABORTED;
    }
}
