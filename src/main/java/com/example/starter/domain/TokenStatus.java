package com.example.starter.domain;

/**
 * 单个刷新令牌状态。
 *
 * <p>ACTIVE：未消费且在有效期内；CONSUMED：已被合法轮换消费；
 * EXPIRED：已到期失效（now &gt;= expiresAt）；REVOKED：随族撤销。
 */
public enum TokenStatus {
    ACTIVE,
    CONSUMED,
    EXPIRED,
    REVOKED
}
