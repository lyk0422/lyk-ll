package com.example.starter.cems;

/**
 * 业务规则冲突：包含同键异参幂等冲突（HTTP 409）、终态排口操作、执行中限排令唯一占槽冲突等。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
