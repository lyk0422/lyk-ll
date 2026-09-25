package com.example.starter.epidemic.service;

import org.springframework.http.HttpStatus;

/** 资源不存在（卡片/病种/流程等）。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
