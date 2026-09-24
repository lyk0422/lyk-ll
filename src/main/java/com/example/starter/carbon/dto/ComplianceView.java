package com.example.starter.carbon.dto;

import java.time.Instant;

/**
 * 企业年度履约账户视图。
 *
 * @param status PENDING 履约中；SURRENDERED 已足额清缴；NON_COMPLIANT 未履约（截止后标记，终态）
 */
public record ComplianceView(
        long enterpriseId,
        int complianceYear,
        long emissions,
        long surrenderedAllowance,
        long surrenderedOffset,
        String status,
        Instant deadlineAt) {
}
