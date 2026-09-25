package com.example.starter.water.support;

/**
 * 业务规则拒绝（额满、封存终态等），映射为 HTTP 422。
 */
public class BusinessRejectedException extends RuntimeException {

    public BusinessRejectedException(String message) {
        super(message);
    }
}
