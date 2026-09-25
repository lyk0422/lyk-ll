package com.example.starter.rebate.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 申报批次详情，含校验明细与疑点。
 *
 * @param id           批次 ID
 * @param batchNo      批次编号
 * @param declareDate  申报日（UTC）
 * @param status       批次状态
 * @param taxTotal     申报税额合计（元）
 * @param details      关联校验明细
 * @param doubts       疑点列表
 * @param createdAt    创建时间（UTC）
 * @param declaredAt   正式申报时间（UTC）
 * @param approvedAt   审核时间（UTC）
 * @param refundedAt   退库时间（UTC）
 * @param voidedAt     作废时间（UTC）
 * @param closedAt     结案时间（UTC）
 */
public record BatchResponse(
        Long id,
        String batchNo,
        LocalDate declareDate,
        String status,
        BigDecimal taxTotal,
        List<ValidationDetailResponse> details,
        List<DoubtResponse> doubts,
        Instant createdAt,
        Instant declaredAt,
        Instant approvedAt,
        Instant refundedAt,
        Instant voidedAt,
        Instant closedAt
) {
}
