package com.example.starter.planningscene.dto;

/**
 * 400 参数/业务规则错误响应体。
 *
 * @param error 错误代码
 * @param message 可读说明（整批回滚时的拒绝原因）
 */
public record ApiErrorResponse(String error, String message) {
}
