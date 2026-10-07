package com.example.starter.metro.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 缴回回执（合成银行收款凭据）请求：一笔到账含全局唯一合成银行流水号、
 * UTC 到账时刻与 1 至 10 条不同线路分配；分配合计须等于总额且每条为正、
 * 不超过该线路未缴余额，任一不符整笔零入账。
 *
 * @param requestId       幂等键
 * @param statementNo     缴回对账单号
 * @param bankSerialNo    合成银行流水号（全局唯一，换批/改时刻/改金额重复提交为 409）
 * @param arrivalTimeUtc  UTC 到账时刻（始终以 UTC 表示，如 2026-09-27T01:00:00Z）
 * @param totalCents      到账总额（分，正整数）
 * @param allocations     逐线路分配：1 至 10 条，线路互不相同
 * @param expectedVersion 对账单期望版本，旧版 409，成功后版本加 1
 */
public record RemittanceReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String statementNo,
        @NotBlank String bankSerialNo,
        @NotNull Instant arrivalTimeUtc,
        @Positive @NotNull Long totalCents,
        @NotEmpty @Size(min = 1, max = 10) @Valid List<Allocation> allocations,
        @Positive @NotNull Integer expectedVersion) {

    /** 回执逐线路分配（金额为正，分）。 */
    public record Allocation(
            @NotBlank String lineCode,
            @Positive @NotNull Long amountCents) {
    }
}
