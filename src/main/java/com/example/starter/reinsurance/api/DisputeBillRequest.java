package com.example.starter.reinsurance.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 账单异议请求。异议必须逐项列明差异原因。
 *
 * @param requestId 幂等键
 * @param items 异议明细（至少一项）
 */
public record DisputeBillRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<DisputeItemRequest> items) {

    /**
     * 单项异议。
     *
     * @param itemRef 异议明细行号（如 "L2"）或业务关联号（保单号/赔案号）
     * @param expectedAmount 异议方认为正确的金额（元）
     * @param reason 该项差异原因（必填）
     */
    public record DisputeItemRequest(
            @NotBlank String itemRef,
            @NotNull BigDecimal expectedAmount,
            @NotBlank String reason) {
    }
}
