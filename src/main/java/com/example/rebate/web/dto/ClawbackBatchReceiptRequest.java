package com.example.rebate.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 汇总到账分配请求：一笔真实银行流水（母凭证）分给同一结案批次内 1 至 10 张不同的已批准冲回单。
 *
 * @param bankSerial 唯一真实银行流水号（与旧单笔回执流水全局互斥），纳入指纹
 * @param receivedAt 到账时刻（UTC，ISO-8601，含时区），纳入指纹
 * @param totalCents 到账总额（正数整数分），须精确等于各分配行合计
 * @param allocations 分配行（1 至 10 张不同冲回单，各自正数且不超未收回金额）
 */
public record ClawbackBatchReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String bankSerial,
        @NotNull Instant receivedAt,
        @NotNull @Positive Long totalCents,
        @NotNull @Size(min = 1, max = 10) List<@Valid AllocationLine> allocations) {

    /**
     * 分配行：目标冲回单（更正编号）、分摊金额与目标追缴明细期望版本。
     *
     * @param correctionNo 目标冲回单更正编号（同批次内唯一，须为已批准单）
     * @param amountCents 本行分摊金额（正数整数分，不超该单未收回金额）
     * @param expectedVersion 目标追缴明细期望版本（从 1 开始，成功分配后加 1）
     */
    public record AllocationLine(
            @NotBlank String correctionNo,
            @NotNull @Positive Long amountCents,
            @NotNull @Positive Integer expectedVersion) {
    }
}
