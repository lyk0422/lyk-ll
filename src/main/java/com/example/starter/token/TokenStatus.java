package com.example.starter.token;

/**
 * 刷新令牌生命周期状态。
 */
public enum TokenStatus {

    /** 可用于轮换。 */
    ACTIVE,

    /** 已被成功轮换消费，不可再次使用；同键重放除外。 */
    CONSUMED,

    /** 因登出或重放检测随令牌族被撤销。 */
    REVOKED,

    /** 已达到到期时间（now &gt;= expiresAt）。 */
    EXPIRED
}
