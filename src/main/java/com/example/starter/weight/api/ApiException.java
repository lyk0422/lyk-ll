package com.example.starter.weight.api;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /**
     * 404 资源不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    /**
     * 409 冲突：幂等键冲突、状态冲突、并发唯一约束冲突。
     */
    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    /**
     * 422 业务规则不满足（如复检逾期）。
     */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }
}
