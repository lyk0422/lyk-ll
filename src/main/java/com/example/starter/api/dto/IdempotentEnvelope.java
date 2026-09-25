package com.example.starter.api.dto;

/**
 * 幂等包装：同键同参重放返回首次结果并标记 replayed=true；首次受理为 false。
 */
public record IdempotentEnvelope<T>(T data, boolean replayed) {
}
