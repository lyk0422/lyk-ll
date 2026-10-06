package com.example.starter.service;

/**
 * 服务调用结果：HTTP 状态码与响应体，幂等命中时原样回放首次结果。
 */
public record ServiceResult(int status, Object body) {
}
