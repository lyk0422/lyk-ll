package com.example.starter.api.dto;

/**
 * 方案审核结果。整单拒绝时 accepted=false 并给出分类与原因。
 */
public record PlanResultResponse(
        String requestId,
        Long planId,
        String planNo,
        String kilnId,
        String status,
        boolean accepted,
        String rejectCategory,
        String rejectReason,
        java.math.BigDecimal chlorineTotalKg,
        java.math.BigDecimal sulfurTotalKg,
        java.time.LocalDateTime submittedAt) {
}
