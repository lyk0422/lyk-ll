package com.example.starter.waste;

import java.time.LocalDateTime;

/**
 * 异常核查单响应，含结论留痕字段。
 */
public record ExceptionCaseResponse(
        Long exceptionId,
        Long manifestId,
        String status,
        String conclusionType,
        String conclusionNote,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {
}
