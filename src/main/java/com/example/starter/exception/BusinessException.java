package com.example.starter.exception;

/** 请求引用资源不存在或状态不允许该操作，映射 HTTP 422。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
