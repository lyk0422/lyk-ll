package com.example.starter.support;

/**
 * 业务规则不满足（HTTP 422）：如续期不在受理窗口内、资格已失效等。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
