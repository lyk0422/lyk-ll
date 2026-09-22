package com.example.starter.error;

/**
 * 统一错误响应体；errorCode 稳定且彼此可区分。
 *
 * @param errorCode 业务错误码，见 {@link ErrorCode}
 * @param message   面向调用者的错误描述
 */
public record ApiErrorResponse(String errorCode, String message) {
}
