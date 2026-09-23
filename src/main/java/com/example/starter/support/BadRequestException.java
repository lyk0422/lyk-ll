package com.example.starter.support;

/**
 * 请求参数非法（含体积精度非法），映射为 HTTP 400。
     */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
