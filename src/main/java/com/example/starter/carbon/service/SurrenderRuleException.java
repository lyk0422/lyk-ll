package com.example.starter.carbon.service;

/**
 * 申请语义错误（余额不足、超5%抵减、逾期、未履约等），映射 HTTP 422。
 */
public class SurrenderRuleException extends RuntimeException {

    /** 机器可读错误码。 */
    private final String code;

    public SurrenderRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
