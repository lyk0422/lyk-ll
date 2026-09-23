package com.example.starter.token;

/**
 * 请求被拒绝的业务异常，由控制器映射为对应的 HTTP 4xx 状态码。
 */
public class TokenServiceException extends RuntimeException {

    /** 建议的 HTTP 状态码。 */
    private final int status;

    public TokenServiceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
