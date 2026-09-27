package com.example.starter.domain;

/**
 * 进口通关单状态。
 */
public enum CustomsDeclarationStatus {
    /** 有效在册，可绑定子批并核销 */
    ACTIVE,
    /** 已撤销：未签发子批预占全部释放并阻断签发，已签发证据保留并记风险事件 */
    REVOKED
}
