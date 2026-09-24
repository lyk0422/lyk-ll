package com.example.starter.payment;

/**
 * 业务异常，携带 HTTP 状态语义，由全局异常处理器映射为响应。
 */
public class BizException extends RuntimeException {

    private final int httpStatus;

    public BizException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 400 请求参数或业务前置条件不合法。 */
    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    /** 404 资源不存在。 */
    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    /** 409 幂等冲突 / 状态冲突。 */
    public static BizException conflict(String message) {
        return new BizException(409, message);
    }
}
