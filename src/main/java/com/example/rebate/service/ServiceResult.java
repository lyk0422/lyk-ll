package com.example.rebate.service;

/**
 * 业务动作结果：HTTP 状态码 + 响应体对象（由幂等编排序列化存档）。
 */
public record ServiceResult(int status, Object body) {

    public static ServiceResult ok(Object body) {
        return new ServiceResult(200, body);
    }
}
