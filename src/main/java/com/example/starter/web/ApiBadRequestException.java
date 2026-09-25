package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 请求不合法（目标低于技术出力下限、状态不允许该操作等），映射 HTTP 400。
 */
public class ApiBadRequestException extends RuntimeException {

    public ApiBadRequestException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
