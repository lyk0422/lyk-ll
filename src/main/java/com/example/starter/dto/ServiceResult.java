package com.example.starter.dto;

/**
 * 服务层统一返回：HTTP 状态码 + 已序列化好的响应体对象。
 */
public record ServiceResult(int httpStatus, Object body) {

    public static ServiceResult ok(Object body) {
        return new ServiceResult(200, body);
    }

    public static ServiceResult status(int httpStatus, Object body) {
        return new ServiceResult(httpStatus, body);
    }
}
