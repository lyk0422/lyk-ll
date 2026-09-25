package com.example.starter.radio.service;

/**
 * 业务规则拒绝异常，映射 HTTP 422：活度勾稽不平衡、状态机不允许的操作等。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
