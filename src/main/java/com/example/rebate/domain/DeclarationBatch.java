package com.example.rebate.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 退税申报批次。
 *
 * @param declareDate 申报日（UTC 日界），退税率取值依据
 * @param totalDeclaredTax 申报税额合计
 * @param frozenAt 正式申报冻结时刻，{@code null} 未冻结
 * @param voidedAt 撤批作废时刻，{@code null} 未作废
 * @param closedAt 结案时刻，{@code null} 未结案
 */
public record DeclarationBatch(
        Long id,
        String batchNo,
        BatchStatus status,
        LocalDate declareDate,
        BigDecimal totalDeclaredTax,
        Instant frozenAt,
        Instant voidedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt) {

    public DeclarationBatch withStatus(BatchStatus newStatus, Instant now) {
        return new DeclarationBatch(id, batchNo, newStatus, declareDate, totalDeclaredTax,
                frozenAt, voidedAt, closedAt, createdAt, now);
    }
}
