package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 请求目标不存在（货件不存在等），映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    /**
     * @return 该异常对应的 HTTP 状态码
     */
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
