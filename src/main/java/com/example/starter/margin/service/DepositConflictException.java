package com.example.starter.margin.service;

/**
 * 同 requestId 但指纹（账户/金额/币种）不一致：返回 409，且不得重复入账。
 */
public class DepositConflictException extends RuntimeException {

    public DepositConflictException(String requestId) {
        super("requestId 已存在但请求参数指纹不一致: " + requestId);
    }
}
