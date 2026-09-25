package com.example.starter.support;

/**
 * 业务异常：携带 HTTP 状态码（如 404 资源不存在、409 同键异参/状态冲突、422 业务规则不满足）。
 */
public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    public static BusinessException unprocessable(String message) {
        return new BusinessException(422, message);
    }
}
