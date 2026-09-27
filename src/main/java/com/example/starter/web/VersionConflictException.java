package com.example.starter.web;

/**
 * 乐观锁版本冲突（HTTP 409）：expectedVersion 与当前版本不一致。
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
