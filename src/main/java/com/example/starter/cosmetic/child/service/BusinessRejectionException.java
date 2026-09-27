package com.example.starter.cosmetic.child.service;

/**
 * 业务拒绝异常，映射为 HTTP 422：版本不一致、状态不允许的操作、
 * 伪装简易变更等业务规则拒绝。发生业务拒绝时事务回滚，幂等键不被占用。
 */
public class BusinessRejectionException extends RuntimeException {

    /** 业务拒绝错误码（机器可读）。 */
    private final String code;

    public BusinessRejectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
