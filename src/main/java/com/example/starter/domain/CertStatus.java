package com.example.starter.domain;

/**
 * 证书状态。
 */
public enum CertStatus {

    /** 有效。 */
    VALID,
    /** 已作废（批次注销时同步作废并留痕）。 */
    VOID
}
