package com.example.starter.ros.domain;

/**
 * 注册中心业务异常，携带可区分的 {@link RegistryErrorCode}。
 */
public class RegistryException extends RuntimeException {

    private final RegistryErrorCode errorCode;

    public RegistryException(RegistryErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RegistryErrorCode getErrorCode() {
        return errorCode;
    }
}
