package com.example.starter.tf.api;

import java.util.Map;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String code, String message, Map<String, Object> details) {
}
