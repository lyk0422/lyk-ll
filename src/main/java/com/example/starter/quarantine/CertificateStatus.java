package com.example.starter.quarantine;

/**
 * 检疫合格证状态机：ISSUED → IN_TRANSIT → CLOSED；封锁/超时触发 VOIDED/EXPIRED。
 */
public enum CertificateStatus {

    /** 已出证未启运。 */
    ISSUED,
    /** 调运在途。 */
    IN_TRANSIT,
    /** 已作废（出证后未启运即遇疫点封锁）。 */
    VOIDED,
    /** 证失效（超过有效期终点仍未完成落地）。 */
    EXPIRED,
    /** 已结案（终态，累计落地数量等于证载数量）。 */
    CLOSED
}
