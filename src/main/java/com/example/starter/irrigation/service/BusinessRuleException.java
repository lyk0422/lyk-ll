package com.example.starter.irrigation.service;

/**
 * 业务前置条件不满足：轮灌组不存在、斗口不属于该组、加购时无欠供等，映射 HTTP 422
 * （编组等资源不存在语义由 {@link NotFoundException} 承担，映射 404）。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
