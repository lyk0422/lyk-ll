package com.example.starter.legality.service;

/**
 * 请求参数不满足业务前置条件（如结束早于报到、批次内成员重复）。映射 HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
