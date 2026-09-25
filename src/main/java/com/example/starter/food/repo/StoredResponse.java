package com.example.starter.food.repo;

/**
 * 已完成的幂等请求首次响应。
 */
public record StoredResponse(String fingerprint, int statusCode, String responseJson) {
}
