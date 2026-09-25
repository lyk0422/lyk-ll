package com.example.starter.ride;

/**
 * 统一 API 响应。httpStatus 用于幂等重放时还原首次响应码。
 */
public record ApiResult(int httpStatus, String message, Object data) {

    public static ApiResult ok(String message, Object data) {
        return new ApiResult(200, message, data);
    }

    public static ApiResult conflict(String message) {
        return new ApiResult(409, message, null);
    }
}
