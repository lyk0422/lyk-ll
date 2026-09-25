package com.example.starter.pension.dto;

import java.time.Instant;

import com.example.starter.pension.domain.PlanStatus;

/**
 * 结清结果：结清时未归属企业缴费在同一事务划回企业账户；重复结清幂等成功。
 */
public record SettleResponse(
        String planNo,
        PlanStatus status,
        boolean alreadySettled,
        long companyReturnCents,
        Instant settledAtUtc) {
}
