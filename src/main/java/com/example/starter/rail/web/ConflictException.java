package com.example.starter.rail.web;

/**
 * 请求与当前状态冲突（非法迁移、版本不符、异参复用请求键等），对应 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
