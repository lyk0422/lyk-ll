package com.example.starter.domain;

/**
 * 进口通关单状态。
 */
public enum CustomsStatus {
    /** 有效：可绑定、可核销 */
    ACTIVE,
    /** 已撤销：释放全部未签发子批预占并阻断签发，已签发证据不变 */
    REVOKED
}
