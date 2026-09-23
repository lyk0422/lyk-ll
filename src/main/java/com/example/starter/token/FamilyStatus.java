package com.example.starter.token;

/**
 * 令牌族（会话）状态。
 */
public enum FamilyStatus {

    /** 族内允许正常轮换。 */
    ACTIVE,

    /** 已终态撤销（登出或检测到重放攻击），族内任何令牌都不得再轮换。 */
    REVOKED
}
