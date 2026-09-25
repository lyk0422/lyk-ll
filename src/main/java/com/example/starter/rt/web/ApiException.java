package com.example.starter.rt.web;

/**
 * 业务规则拒绝异常：由全局异常处理器映射为对应 HTTP 状态码。
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final boolean alert;

    public ApiException(int httpStatus, String code, String message) {
        this(httpStatus, code, message, false);
    }

    public ApiException(int httpStatus, String code, String message, boolean alert) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.alert = alert;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public boolean isAlert() {
        return alert;
    }

    /** 409 冲突：幂等指纹冲突、唯一在执行计划冲突等。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    /** 422 业务校验拒绝：剂量超限、未验证先执行等。 */
    public static ApiException rejected(String code, String message) {
        return new ApiException(422, code, message);
    }

    /** 404 资源不存在。 */
    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    /** 跳次拒绝：409 且同时触发告警（alert=true）。 */
    public static ApiException skipWarn(String code, String message) {
        return new ApiException(409, code, message, true);
    }
}
