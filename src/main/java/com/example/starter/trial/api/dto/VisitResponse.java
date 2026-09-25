package com.example.starter.trial.api.dto;

import java.time.Instant;

/**
 * 访视记录视图。{@code excludedFromMainAnalysis} 为真表示排除出主要分析集（不可回退）。
 */
public record VisitResponse(
        String trialCode,
        String screeningNo,
        String visitCode,
        Instant visitTimeUtc,
        boolean excludedFromMainAnalysis) {
}
