package com.example.starter.service;

/**
 * 服务层响应：HTTP 状态码 + JSON 报文，供幂等重放原样返回。
 *
 * @param httpStatus HTTP状态码
 * @param jsonBody   JSON响应报文
 */
public record ServiceResponse(int httpStatus, String jsonBody) {
}
