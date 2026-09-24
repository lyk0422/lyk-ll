package com.example.starter.portability.dto;

/**
 * 幂等处理结果：HTTP 状态码与已序列化的 JSON 响应体，重放时原样返回。
 */
public record StoredResponse(int httpStatus, String body) {
}
