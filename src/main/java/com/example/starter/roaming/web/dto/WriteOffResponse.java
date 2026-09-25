package com.example.starter.roaming.web.dto;

import java.time.Instant;

/**
 * 差异挂账核销响应；同一挂账只允许核销一次。
 */
public record WriteOffResponse(
        String requestId,
        String discrepancyNo,
        String sessionNo,
        int meterSeq,
        long meterWh,
        String status,
        Instant writeOffAt,
        String writeOffBy,
        String writeOffRemark) {
}
