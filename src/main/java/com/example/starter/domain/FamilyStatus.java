package com.example.starter.domain;

/**
 * 令牌族状态。
 *
 * <p>ACTIVE：族内当前令牌可正常轮换；REVOKED：族已撤销（登出或检测到重放），
 * 族内任何令牌都不得再轮换。
 */
public enum FamilyStatus {
    ACTIVE,
    REVOKED
}
