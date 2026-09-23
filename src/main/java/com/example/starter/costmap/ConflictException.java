package com.example.starter.costmap;

/**
 * 版本冲突或幂等请求异参重放，HTTP 409。
 */
public class ConflictException extends CostmapException {

    public ConflictException(String code, String message) {
        super(code, message);
    }

    public ConflictException(String code, String message, Throwable cause) {
        super(code, message);
        initCause(cause);
    }
}
