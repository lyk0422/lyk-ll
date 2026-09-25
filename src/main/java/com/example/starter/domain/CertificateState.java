package com.example.starter.domain;

/**
 * 证书状态。
 */
public enum CertificateState {
    /** 有效 */
    VALID,
    /** 已作废（批次注销时同步作废） */
    REVOKED
}
