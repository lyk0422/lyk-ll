package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/** 资源不存在（404）。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
