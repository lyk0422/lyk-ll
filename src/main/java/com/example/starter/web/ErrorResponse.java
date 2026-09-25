package com.example.starter.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param code    机器可读错误码
 * @param message 错误描述
 * @param path    请求路径
 * @param at      错误发生时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String path, Instant at) {
}
