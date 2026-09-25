package com.example.starter.pension.api;

import com.example.starter.pension.domain.PlanStatus;

import java.time.OffsetDateTime;

/**
 * 计划结清结果。
 *
 * @param planNo             计划号
 * @param status             结清后状态（SETTLED）
 * @param replayed           true 表示对已结清计划的重复结清（幂等成功）
 * @param vestedTotalCents   归属权益快照总额，单位：分
 * @param paidTotalCents     成员累计出款总额，单位：分
 * @param returnedTotalCents 划回企业的未归属金额，单位：分
 * @param settledAt          结清时间（UTC）
 */
public record SettleResponse(
        String planNo,
        PlanStatus status,
        boolean replayed,
        long vestedTotalCents,
        long paidTotalCents,
        long returnedTotalCents,
        OffsetDateTime settledAt) {
}
