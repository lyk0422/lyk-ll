package com.example.starter.api;

// 结案结果：CLOSED 为终态；idempotentReplay=TRUE 表示重复结案的幂等成功。
public record CloseResponse(
        String operationNo,
        String requestId,
        String status,
        long closedAt,
        boolean idempotentReplay) {
}
