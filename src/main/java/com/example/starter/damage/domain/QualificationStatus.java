package com.example.starter.damage.domain;

/**
 * 供应商资格版本状态。
 */
public enum QualificationStatus {
    /** 有效：在 [validFrom, validTo) 区间内且未撤销 */
    ACTIVE,
    /** 已撤销：记录撤销时刻与原因 */
    REVOKED
}
