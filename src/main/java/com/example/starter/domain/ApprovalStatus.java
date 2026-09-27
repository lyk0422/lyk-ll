package com.example.starter.domain;

/**
 * 补偿批准状态：ACTIVE 未投料；CONSUMED 已投料冻结（历史不追改）；INVALIDATED 系数修订失效。
 */
public enum ApprovalStatus {
    ACTIVE,
    CONSUMED,
    INVALIDATED
}
