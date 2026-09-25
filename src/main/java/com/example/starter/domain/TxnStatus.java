package com.example.starter.domain;

/** 交易状态：POSTED 已入账；SUSPENDED 封账后挂账；MOVED 已调账入新批。 */
public enum TxnStatus {
    POSTED,
    SUSPENDED,
    MOVED
}
