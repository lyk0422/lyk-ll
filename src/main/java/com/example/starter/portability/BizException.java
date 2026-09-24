package com.example.starter.portability;

/**
 * 业务异常：携带 HTTP 状态码与业务错误码，由全局异常处理器转换为统一错误响应。
 */
public class BizException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public BizException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
