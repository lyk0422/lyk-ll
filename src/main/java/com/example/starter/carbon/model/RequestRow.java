package com.example.starter.carbon.model;

import java.time.Instant;

/**
 * 清缴申请行（幂等记录，含凭证号）。
 */
public record RequestRow(
        String requestId,
        String fingerprint,
        long enterpriseId,
        int complianceYear,
        long allowanceAmount,
        long offsetAmount,
        String certificateNo,
        Instant createdAt) {
}
