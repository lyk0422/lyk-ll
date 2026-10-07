package com.example.starter.metro.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 缴回收款凭据（合成银行回执）请求：整笔入账或整笔零入账。
 *
 * @param requestId       幂等键
 * @param statementNo     对账单号
 * @param batchNo         来源调账批次号（须与对账单一致，不同批次不得混用）
 * @param bankFlowNo      合成银行流水号（全局唯一）
 * @param arrivedAt       UTC 到账时刻
 * @param totalCents      回执总额（分，正数），须等于各线路分配合计
 * @param expectedVersion 对账单期望版本
 * @param allocations     1 至 10 条不同线路分配，每条为正且不超该线路未缴余额
 */
public record RemittanceReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String statementNo,
        @NotBlank String batchNo,
        @NotBlank String bankFlowNo,
        @NotNull Instant arrivedAt,
        @Positive @NotNull Long totalCents,
        @Positive @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 10) List<@Valid @NotNull LineAllocation> allocations) {

    /**
     * 单条线路分配。
     *
     * @param lineCode    线路编码
     * @param amountCents 分配金额（分，正数）
     */
    public record LineAllocation(@NotBlank String lineCode, @Positive @NotNull Long amountCents) {
    }
}
