package com.example.starter.roaming.error;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在（车辆/桩/会话/挂账等）。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
