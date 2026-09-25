package com.example.starter.service;

/**
 * 状态或幂等冲突（HTTP 409）：同键异参、站内已有执行中任务、非法状态流转等。
 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 409;
    }
}
