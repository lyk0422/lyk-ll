package com.example.starter.cosmetic.service;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转换为响应。
 */
public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 建议的 HTTP 状态码。
     */
    public int status() {
        return status;
    }

    /**
     * 资源不存在（404）。
     */
    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    /**
     * 状态冲突或幂等键冲突（409）。
     */
    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    /**
     * 业务规则拒绝（422）。
     */
    public static BusinessException unprocessable(String message) {
        return new BusinessException(422, message);
    }
}
