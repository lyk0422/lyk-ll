package com.example.starter.service;

/**
 * 业务规则不满足（HTTP 422）：步进超限、稳定期未满、开度越界等。
 */
public class UnprocessableException extends BusinessException {

    public UnprocessableException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 422;
    }
}
