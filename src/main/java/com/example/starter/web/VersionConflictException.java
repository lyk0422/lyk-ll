package com.example.starter.web;

/**
 * 乐观版本冲突（HTTP 409）：请求携带的 expectedVersion 与储罐当前版本不一致。
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
