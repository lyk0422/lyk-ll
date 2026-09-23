package com.example.starter.playout;

import org.springframework.http.HttpStatus;

/**
 * 业务异常；携带映射到 HTTP 响应的状态码。
 */
public class PlayoutException extends RuntimeException {

    private final HttpStatus status;

    public PlayoutException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
