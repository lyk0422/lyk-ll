package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务请求无法处理（参数语义错误、缺号、时间倒退等），映射 HTTP 422。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }

    /**
     * @return 该异常对应的 HTTP 状态码
     */
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
