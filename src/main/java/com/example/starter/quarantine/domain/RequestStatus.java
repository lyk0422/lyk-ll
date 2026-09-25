package com.example.starter.quarantine.domain;

/**
 * 检疫申报单据状态。
 */
public enum RequestStatus {

    /** 已申报，等待产地检疫出证。 */
    SUBMITTED,
    /** 产地被划为疫点封锁区，未出证申报被驳回（终态）。 */
    REJECTED,
    /** 已出证但未启运时封锁发布，证被自动作废（终态）。 */
    VOID,
    /** 已出证，等待启运。 */
    CERTIFIED,
    /** 已启运，调运在途。 */
    IN_TRANSIT,
    /** 落地报告超过证有效期，证失效（终态）。 */
    EXPIRED,
    /** 落地累计数量等于证载数量，结案（终态）。 */
    CLOSED
}
