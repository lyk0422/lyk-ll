package com.example.starter.web.dto;

/**
 * 历史水下记录更正结果：独立差异，不回写已关闭潜次。
 */
public record CorrectionResult(
        String requestId,
        String diveRequestId,
        String diverCode,
        int oldBottomMin,
        int newBottomMin,
        int deltaMin,
        String correctedAtUtc,
        boolean replayed) {
}
