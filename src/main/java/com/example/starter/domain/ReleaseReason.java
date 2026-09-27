package com.example.starter.domain;

/**
 * 绑定预占释放原因。
 */
public enum ReleaseReason {
    /** 签发前企业撤回子批，释放后该批按国内批次处理 */
    WITHDRAWN,
    /** 通关单撤销强制释放，该子批签发被阻断 */
    DECLARATION_REVOKED
}
