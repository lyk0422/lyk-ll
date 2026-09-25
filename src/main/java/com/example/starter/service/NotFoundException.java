package com.example.starter.service;

/**
 * 资源不存在（HTTP 404）。
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 404;
    }
}
