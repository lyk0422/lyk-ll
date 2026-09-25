package com.example.starter.api;

// 出水登记结果：status=OBSERVING，observationDueAt=exitAt+7200（UTC 纪元秒）。
public record ExitResponse(
        String operationNo,
        String requestId,
        String status,
        long exitAt,
        long observationDueAt) {
}
