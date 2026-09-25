package com.example.starter.pv.dto;

import java.time.Instant;

import com.example.starter.pv.CaseStatus;

/**
 * 个例关闭结果。重复关闭返回首次关闭的相同结果（幂等成功）。
 */
public record CloseCaseResponse(
        String caseNo,
        CaseStatus status,
        Instant closedAt,
        int caseVersion) {
}
