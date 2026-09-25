package com.example.starter.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 转关服务对外 DTO 集合。
 */
public final class TransferDtos {

    private TransferDtos() {
    }

    /** 申请商品项：税号、数量、单价。 */
    public record ItemRequest(
            @NotBlank String tariffCode,
            @Min(1) long quantity,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal unitPrice) {
    }

    /** 转关申请请求。 */
    public record ApplyRequest(
            @NotBlank String requestId,
            @NotBlank String enterpriseId,
            @NotBlank String fromWarehouse,
            @NotBlank String toWarehouse,
            @NotEmpty List<@Valid ItemRequest> items) {
    }

    /** 商品项视图：核注数量与累计已核销数量。 */
    public record ItemView(String tariffCode, long quantity, BigDecimal unitPrice, long verifiedQuantity) {
    }

    /** 转关单视图。 */
    public record OrderResponse(
            long orderId,
            String requestId,
            String enterpriseId,
            String fromWarehouse,
            String toWarehouse,
            String status,
            Instant endorsedAt,
            Instant verifyDeadline,
            Instant createdAt,
            List<ItemView> items) {
    }

    /** 核销商品项：税号与本批核销数量。 */
    public record VerifyItemRequest(
            @NotBlank String tariffCode,
            @Min(1) long quantity) {
    }

    /** 核销请求：requestId 幂等，允许分批部分核销。 */
    public record VerifyRequest(
            @NotBlank String requestId,
            @NotEmpty List<@Valid VerifyItemRequest> items) {
    }

    /** 核销结果：含核销后单据状态与是否结案。 */
    public record VerifyResponse(
            String requestId,
            long orderId,
            String status,
            boolean closed,
            List<ItemView> items) {
    }

    /** 状态变更结果（核注/撤销/解除异常）。 */
    public record StatusChangeResponse(long orderId, String status) {
    }

    /** 状态留痕条目。 */
    public record HistoryEntry(int seq, String fromStatus, String toStatus, Instant changedAt, String note) {
    }

    /** 单据详情：单据视图 + 按序状态留痕。 */
    public record OrderDetailResponse(OrderResponse order, List<HistoryEntry> history) {
    }

    /** 海关复核解除异常请求。 */
    public record ReleaseRequest(@NotBlank String reviewer, String note) {
    }

    /** 解除异常结果：企业解冻及恢复在途的单据列表。 */
    public record ReleaseResponse(String enterpriseId, boolean frozen, List<Long> restoredOrderIds) {
    }
}
