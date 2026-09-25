package com.example.starter.feed.domain;

/**
 * 养殖批次状态：ACTIVE 在栏（可能处于休药期内），SLAUGHTERED 已出栏（终态）。
 */
public enum BatchStatus {
    /** 在栏饲养中。 */
    ACTIVE,
    /** 已出栏，终态，不可再被任何业务操作回退。 */
    SLAUGHTERED
}
