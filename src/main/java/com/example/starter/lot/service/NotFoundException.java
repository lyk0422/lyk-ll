package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在异常（404）。
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
