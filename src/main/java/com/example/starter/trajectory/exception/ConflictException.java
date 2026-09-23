package com.example.starter.trajectory.exception;

/**
 * 请求与已存在资源状态冲突（如幂等键同键异参、规则版本重复发布），对应 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
