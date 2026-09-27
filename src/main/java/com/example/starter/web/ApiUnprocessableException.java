package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务规则拒绝（参数合法但业务上不可实现），映射 HTTP 422。
 * 例：计划缺段/重复机组/超装机整版拒绝；计划绑定目标按可参与出力不可实现；
 * 过期版本不允许绑定；绑定计划与日期/时段不一致。
 */
public class ApiUnprocessableException extends RuntimeException {

    public ApiUnprocessableException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
