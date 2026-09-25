package com.example.starter.quarantine;

/**
 * 落地报告处理结果。
 */
public enum ArrivalResult {

    /** 部分到达已受理，累计数量未达证载数量。 */
    ACCEPTED_PARTIAL,
    /** 到达已受理，累计数量恰等于证载数量，证结案。 */
    ACCEPTED_FINAL,
    /** 超过有效期终点，拒绝并将证标记为证失效。 */
    REJECTED_EXPIRED,
    /** 累计数量超过证载数量，整批拒绝。 */
    REJECTED_OVERFLOW,
    /** 证未处于在途状态（未启运），拒绝。 */
    REJECTED_NOT_IN_TRANSIT,
    /** 证已作废或已失效，拒绝。 */
    REJECTED_CERT_INVALID,
    /** 证已结案，迟到事件只记录不改状态。 */
    LATE_IGNORED
}
