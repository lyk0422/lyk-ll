package com.example.starter.trajectory.service;

/**
 * 同键异参：requestId 已存在但请求参数不一致，返回 409。
 */
public class ConflictingRequestException extends RuntimeException {

    public ConflictingRequestException(String requestId) {
        super("conflicting parameters for requestId: " + requestId);
    }
}
