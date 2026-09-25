package com.example.starter.roaming.error;

import org.springframework.http.HttpStatus;

/**
 * 冲突：同 requestId 异参、并发唯一会话竞争失败、挂账已核销等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
