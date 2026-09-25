package com.example.starter.error;

import com.example.starter.domain.UnitStatus;

/**
 * 业务异常基类，携带 HTTP 状态码、业务错误码与可选的单位当前状态。
 */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final transient UnitStatus currentStatus;

    protected ApiException(String code, String message, UnitStatus currentStatus) {
        super(message);
        this.code = code;
        this.currentStatus = currentStatus;
    }

    public String getCode() {
        return code;
    }

    public UnitStatus getCurrentStatus() {
        return currentStatus;
    }

    /**
     * 对应的 HTTP 状态码。
     */
    public abstract int httpStatus();
}
