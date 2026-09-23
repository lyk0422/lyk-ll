package com.example.starter.dr;

import org.springframework.http.HttpStatus;

/**
 * 目标资源不存在（404）。
 */
public class DrNotFoundException extends DrBusinessException {

    public DrNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
