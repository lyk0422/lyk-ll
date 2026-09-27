package com.example.starter.web;

/**
 * 乐观锁版本冲突：请求携带的 expectedVersion 与当前版本不一致，映射 HTTP 409。
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(long expected, long actual) {
        super("version conflict: expectedVersion=" + expected + " but current=" + actual);
    }
}
