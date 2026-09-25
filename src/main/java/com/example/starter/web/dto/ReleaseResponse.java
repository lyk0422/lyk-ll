package com.example.starter.web.dto;

import java.util.List;

/**
 * 出厂核销响应。核销为终态，重复核销返回相同的成功结果。
 *
 * @param batchNo        屠宰批次号
 * @param status         批次状态（RELEASED）
 * @param releasedAt     核销时间（UTC ISO-8601）
 * @param reconciliation 核销时回执勾稽结果
 */
public record ReleaseResponse(
        String batchNo,
        String status,
        String releasedAt,
        List<ReconciliationEntry> reconciliation) {
}
