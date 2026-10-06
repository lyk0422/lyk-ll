package com.example.starter.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与可读错误信息，由全局异常处理器统一输出。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 404 资源不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /**
     * 409 冲突：幂等键参数不一致、同库区重复处置链、处置期间出库等。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /**
     * 422 业务规则不满足：单人登记、药剂过期、库存不足、监测期未满等。
     */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
