package com.example.starter.web;

/**
 * 业务拒绝异常：映射为 HTTP 422，用于容量不满足、资源占用、状态不允许等业务规则拒绝。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
