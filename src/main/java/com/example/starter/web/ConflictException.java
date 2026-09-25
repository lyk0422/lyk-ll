package com.example.starter.web;

/**
 * 业务冲突异常：映射为 HTTP 409，用于同键异参幂等冲突、非法状态流转等场景。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
