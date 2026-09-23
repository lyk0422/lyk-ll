package com.example.starter.rosconfig.exception;

/**
 * 409 冲突：静态参数热更新、版本冲突或同事务键异参数。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
