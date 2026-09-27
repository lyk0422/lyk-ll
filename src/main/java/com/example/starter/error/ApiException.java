package com.example.starter.error;

/**
 * 业务异常：携带 HTTP 状态码与业务错误码，由全局异常处理器转换为统一错误响应。
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

    /** 400：参数非法（格式错误、无法解析的枚举或时刻、集合重复等）。 */
    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    /** 404：批次或头只不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    /** 409：状态冲突、同键异参、勾稽不一致拒绝出厂等。 */
    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    /** 422：业务规则不满足（如 6 小时恰满须重新申报、有条件食用缺标注）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(422, "UNPROCESSABLE", message);
    }
}
