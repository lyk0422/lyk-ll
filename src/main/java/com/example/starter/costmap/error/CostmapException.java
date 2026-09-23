package com.example.starter.costmap.error;

/**
 * 业务异常：携带稳定错误码，由全局异常处理器转换为统一错误响应。
 */
public class CostmapException extends RuntimeException {

    private final ErrorCode code;

    public CostmapException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
