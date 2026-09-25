package com.example.starter.roaming.error;

import org.springframework.http.HttpStatus;

/**
 * 请求可解析但业务状态不允许（车辆停用、状态机非法跳转、读数倒退等）。
 */
public class UnprocessableException extends ApiException {

    public UnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message);
    }
}
