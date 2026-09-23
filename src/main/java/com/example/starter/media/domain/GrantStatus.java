package com.example.starter.media.domain;

/**
 * 授权版本状态。
 * ACTIVE：当前有效；SUPERSEDED：已被更正后的新版本替代；REVOKED：已撤销。
 */
public enum GrantStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}
