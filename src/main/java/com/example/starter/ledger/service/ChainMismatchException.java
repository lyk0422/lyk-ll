package com.example.starter.ledger.service;

/**
 * 电池历史事件链校验失败，映射 HTTP 422；服务同时落库告警并拒绝入站。
 */
public class ChainMismatchException extends RuntimeException {

    public ChainMismatchException(String message) {
        super(message);
    }
}
