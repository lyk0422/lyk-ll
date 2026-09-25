package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 业务规则拒绝异常（HTTP 422）：运统1核对不一致拒绝发车、风压未恢复定压、流程状态不允许等。
 */
public class BusinessException extends ApiException {

    public BusinessException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
