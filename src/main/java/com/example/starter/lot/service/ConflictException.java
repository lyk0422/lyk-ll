package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/**
 * 请求冲突（409）：同键异参指纹不一致、expectedVersion 不匹配、
 * 子批次 ID 已存在、并发超用等。
 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
