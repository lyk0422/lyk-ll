package com.example.starter.transition.dto;

/**
 * 幂等写操作的统一结果包装：标记本次是否为重放（同键同参）。
 */
public record IdempotentResult<T>(
        String requestId,
        boolean replayed,
        T data) {
}
