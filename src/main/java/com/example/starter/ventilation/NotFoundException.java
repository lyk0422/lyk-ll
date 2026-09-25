package com.example.starter.ventilation;

import org.springframework.http.HttpStatus;

/**
 * 404：指定 requestId 的火灾模式或指令不存在。
 */
public class NotFoundException extends ServiceException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
