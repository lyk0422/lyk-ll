package com.example.starter.slaughter.service;

/**
 * 幂等操作类型，作为指纹计算的命名空间。
 */
public enum IdempotentOperation {

    DECLARE,
    PRE_QUARANTINE,
    INSPECTION_REGISTER,
    INSPECTION_COMPLETE,
    JUDGMENT,
    RECEIPT,
    CHECKOUT
}
