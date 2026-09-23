package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 状态或幂等冲突（序号重复改值、版本冲突、幂等键异参、已关闭段补写等），映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    /**
     * @return 该异常对应的 HTTP 状态码
     */
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
