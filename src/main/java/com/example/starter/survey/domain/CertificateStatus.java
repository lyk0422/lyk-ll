package com.example.starter.survey.domain;

/**
 * 证书状态。同一船舶同一检验类型同一时刻至多一张 VALID 证书。
 */
public enum CertificateStatus {
    /** 有效。 */
    VALID,
    /** 已被新证替代（与新证发证在同一事务内置为失效）。 */
    SUPERSEDED,
    /** 一般缺陷逾期未合格复查，证书失效。 */
    EXPIRED
}
