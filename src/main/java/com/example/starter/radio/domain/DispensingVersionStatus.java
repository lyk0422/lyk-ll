package com.example.starter.radio.domain;

/**
 * 分装计算版本状态：ACTIVE 当前有效版本；SUPERSEDED 已被给药时刻变更替代（证据保留）；
 * REJECTED 初算偏差超限（终态证据）。
 */
public enum DispensingVersionStatus {

    /** 当前有效版本；同一分装至多一个 ACTIVE。 */
    ACTIVE,

    /** 已被变更生效后的新版本替代；旧计算与签认永久保留。 */
    SUPERSEDED,

    /** 初算即因超偏差被拒绝的版本。 */
    REJECTED
}
