package com.example.starter.quarantine.domain;

/**
 * 检疫合格证状态。
 */
public enum CertificateStatus {

    /** 有效（含在途）。 */
    VALID,
    /** 封锁发布时已出证未启运，证自动作废（终态）。 */
    VOID,
    /** 落地报告超时，证失效（终态）。 */
    EXPIRED,
    /** 证载动物全部落地，结案（终态）。 */
    CLOSED
}
