package com.example.starter.forestry.support;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转换为响应体。
 */
public class ForestryException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public ForestryException(int httpStatus, String code, String message) {
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

    /**
     * 幂等冲突：同 requestId 不同业务参数，返回 409 且不产生任何副作用。
     */
    public static ForestryException idempotencyConflict(String requestId) {
        return new ForestryException(409, "IDEMPOTENCY_CONFLICT",
                "requestId " + requestId + " 已存在但业务参数不一致");
    }

    /**
     * 资源不存在。
     */
    public static ForestryException notFound(String what) {
        return new ForestryException(404, "NOT_FOUND", what + " 不存在");
    }

    /**
     * 状态冲突（如证件已作废、限额不足、小班已有有效证等）。
     */
    public static ForestryException conflict(String code, String message) {
        return new ForestryException(409, code, message);
    }
}
