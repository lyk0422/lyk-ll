package com.example.starter.gas.web;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在（如未知门站编码）。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
