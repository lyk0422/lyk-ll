package com.example.starter.web;

/**
 * 业务规则拒绝（如安全储备越界、超容量、交接人重复、验收浓度越限等），映射 HTTP 422。
 */
public class BusinessRejectedException extends RuntimeException {

    public BusinessRejectedException(String message) {
        super(message);
    }
}
