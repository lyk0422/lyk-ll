package com.example.starter.domain;

/**
 * 补偿批准状态：VALID 未投料有效；INVALID 批次系数修订导致失效；FED 已投料冻结（历史不追改）。
 */
public enum ApprovalStatus {
    VALID,
    INVALID,
    FED
}
