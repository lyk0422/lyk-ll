package com.example.starter.museum.domain;

/**
 * 借展合同状态：
 * APPLIED 已受理待出库点交；IN_EXHIBITION 在展；SUSPENDED 环境异常挂起；
 * RETURNED 归还点交完成；CLOSED 已结案（终态）。
 */
public enum ContractStatus {
    APPLIED,
    IN_EXHIBITION,
    SUSPENDED,
    RETURNED,
    CLOSED
}
