package com.example.starter.dto;

import java.time.Instant;

/**
 * 企业-品种受理状态视图。
 */
public record EnterpriseStatusView(
        String enterpriseName,
        String productName,
        int consecutiveFailed,
        boolean suspended,
        Instant suspendedAt
) {
}
