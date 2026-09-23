package com.example.starter.dr;

import org.springframework.http.HttpStatus;

/**
 * 状态冲突：重复承诺、版本冲突、同键异参重放等（409）。
 */
public class DrConflictException extends DrBusinessException {

    public DrConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
