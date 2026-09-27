package com.example.starter.web;

/**
 * 业务拒绝异常：映射为 HTTP 422，用于参数合法但业务规则不允许的场景（如容量不足、车辆占用冲突）。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
