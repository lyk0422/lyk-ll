package com.example.rebate.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 汇总分配请求：一笔真实到账分给同一结案批次内 1 至 10 张不同的已批准冲回单。
 *
 * @param masterSerial 唯一真实银行流水号（与旧单笔回执流水全局互斥）
 * @param receivedAt UTC 到账精确时刻，纳入指纹
 * @param totalCents 到账总额（正数整数分），须精确等于各分配金额合计
 * @param allocations 分配行（1 至 10 张不同冲回单；各自正数且不超过该单未收回金额）
 */
public record ClawbackAllocationRequest(
        @NotBlank String requestId,
        @NotBlank String masterSerial,
        @NotNull Instant receivedAt,
        @NotNull @Positive Long totalCents,
        @NotNull @Size(min = 1, max = 10) List<@Valid Item> allocations) {

    /**
     * 单行分配：目标冲回单、分摊金额与该单追缴明细期望版本。
     *
     * @param expectedVersion 目标追缴明细期望版本（成功到账后加 1，旧版 409）
     */
    public record Item(
            @NotBlank String correctionNo,
            @NotNull @Positive Long amountCents,
            @NotNull @Positive Integer expectedVersion) {
    }
}
