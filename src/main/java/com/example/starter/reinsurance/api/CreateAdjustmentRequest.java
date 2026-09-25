package com.example.starter.reinsurance.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 下一期调整单请求。原账单必须已封存，调整项必须引用原账单号。
 *
 * @param requestId 幂等键
 * @param contractNo 合约号
 * @param period 调整单所属季度（下一期，yyyy-Qn）
 * @param refBillNo 被调整的原账单号
 * @param items 调整明细（至少一项）
 */
public record CreateAdjustmentRequest(
        @NotBlank String requestId,
        @NotBlank String contractNo,
        @NotBlank String period,
        @NotBlank String refBillNo,
        @NotEmpty @Valid List<AdjustmentItemRequest> items) {

    /**
     * 单项调整。
     *
     * @param itemType 调整类型：PREMIUM 或 CLAIM_RECOVERY
     * @param refNo 业务关联号（保单号/赔案号）
     * @param grossAmount 带符号基数差额（元）：按合约比例重新计算调整金额
     * @param reason 调整原因（必填）
     */
    public record AdjustmentItemRequest(
            @NotBlank String itemType,
            @NotBlank String refNo,
            @NotNull BigDecimal grossAmount,
            @NotBlank String reason) {
    }
}
