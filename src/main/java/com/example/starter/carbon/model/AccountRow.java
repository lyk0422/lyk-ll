package com.example.starter.carbon.model;

import java.time.Instant;

/**
 * 履约账户行。
 *
 * @param status PENDING 履约中；SURRENDERED 已足额清缴；NON_COMPLIANT 未履约（终态）
 */
public record AccountRow(
        long enterpriseId,
        int complianceYear,
        long emissions,
        long surrenderedAllowance,
        long surrenderedOffset,
        String status,
        Instant deadlineAt) {
}
