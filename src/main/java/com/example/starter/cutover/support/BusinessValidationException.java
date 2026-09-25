package com.example.starter.cutover.support;

/**
 * 请求业务校验失败（窗口超长、状态不允许等），映射 HTTP 400。
 */
public class BusinessValidationException extends RuntimeException {

    public BusinessValidationException(String message) {
        super(message);
    }
}
