package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务规则拒绝（参数合法、对象存在，但业务上不可实现，如计划缺段/超装机、
 * 限电目标按可参与出力无法实现、过期版本不允许绑定），映射 HTTP 422。
 */
public class ApiUnprocessableException extends RuntimeException {

    public ApiUnprocessableException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
