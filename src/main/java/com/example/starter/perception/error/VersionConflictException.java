package com.example.starter.perception.error;

/**
 * 乐观锁版本冲突，映射 HTTP 409；关联请求整体回滚，不产生任何变化。
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
