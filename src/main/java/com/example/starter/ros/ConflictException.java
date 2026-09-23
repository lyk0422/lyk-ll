package com.example.starter.ros;

/**
 * 请求与已有事务 ID 冲突（同键异参），或热更新试图修改静态参数。
 * 统一映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
