package com.example.starter.web;

/**
 * 业务拒绝异常：映射 HTTP 422，用于状态不允许、容量不足、车辆占用冲突等业务规则拒绝。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
