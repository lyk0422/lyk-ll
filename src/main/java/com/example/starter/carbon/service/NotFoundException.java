package com.example.starter.carbon.service;

/**
 * 凭证等资源不存在，映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
