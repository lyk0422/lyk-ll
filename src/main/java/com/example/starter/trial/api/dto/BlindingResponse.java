package com.example.starter.trial.api.dto;

/**
 * 盲态查询结果。未揭盲时 {@code groupCode} 为 null（保持盲态）；已揭盲后返回真实组别。
 */
public record BlindingResponse(
        String trialCode,
        String centerCode,
        String screeningNo,
        long randomNo,
        String blindedCode,
        String groupCode,
        String status,
        java.time.Instant enrolledAtUtc,
        java.time.Instant unblindedAtUtc) {
}
