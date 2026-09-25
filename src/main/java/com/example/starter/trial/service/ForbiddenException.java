package com.example.starter.trial.service;

/**
 * 操作被禁止（HTTP 403）：如非授权人员尝试揭盲确认。
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
