package com.example.starter.trial.api.dto;

import java.time.Instant;

/**
 * 揭盲生效结果；重复揭盲请求原样返回首次结果。
 */
public record UnblindResponse(
        String requestId,
        String trialCode,
        String screeningNo,
        String groupCode,
        String blindedCode,
        String status,
        String firstConfirmer,
        String secondConfirmer,
        Instant effectiveAtUtc) {
}
