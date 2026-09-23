package com.example.starter.signing.model;

/**
 * 签署包流程状态。
 */
public enum PackageStatus {

    /** 进行中：允许签名、修订与取消。 */
    PENDING,
    /** 已完成：最后一个有效签名已提交，证据包已原子冻结，终态。 */
    COMPLETED,
    /** 已取消：终态，迟到签名一律拒绝。 */
    CANCELLED,
    /** 已过期：到达 UTC 到期时间后惰性转入，终态。 */
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
