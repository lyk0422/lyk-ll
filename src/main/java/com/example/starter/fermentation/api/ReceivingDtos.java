package com.example.starter.fermentation.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合批接收请求/响应 DTO。所有写接口以 requestId 幂等；
 * 业务时间字段为本地时间（ISO-8601），台账创建时间按 UTC 记录。
 */
public final class ReceivingDtos {

    private ReceivingDtos() {
    }

    /** 登记合成接收罐请求：正数容量升数，最多三位小数。 */
    public record RegisterReceivingTankRequest(
            @NotBlank String requestId,
            @NotBlank String tankCode,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal capacityLiters) {
    }

    /** 调整接收罐容量请求：不得缩到已接收量以下，旧版 409。 */
    public record UpdateCapacityRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal capacityLiters) {
    }

    /** 登记源批实际清酒升数请求：正数最多三位小数且不超过源发酵罐容量，每批仅一次。 */
    public record RegisterSourceVolumeRequest(
            @NotBlank String requestId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal volumeLiters) {
    }

    /** 合批接收请求：2 至 5 个不同受控源批进入同一接收罐。 */
    public record MergeReceiptRequest(
            @NotBlank String requestId,
            @NotBlank String receivingTankCode,
            @NotNull @Size(min = 2, max = 5) List<Long> sourceBatchIds,
            @NotNull LocalDateTime receivedAt) {
    }

    /** 接收罐响应。 */
    public record ReceivingTankResponse(
            long id,
            String tankCode,
            BigDecimal capacityLiters,
            BigDecimal receivedLiters,
            int version,
            LocalDateTime createdAt) {
    }

    /** 源批实际清酒升数台账响应。 */
    public record SourceVolumeResponse(
            long id,
            long batchId,
            BigDecimal volumeLiters,
            int version,
            LocalDateTime createdAt) {
    }

    /** 合批接收来源明细响应。 */
    public record MergeReceiptSourceResponse(
            long batchId,
            String batchNo,
            BigDecimal volumeLiters) {
    }

    /** 合批接收单响应：来源明细合计等于 totalLiters，可复算。 */
    public record MergeReceiptResponse(
            long id,
            String receivingTankCode,
            String curveCode,
            int curveVersion,
            BigDecimal totalLiters,
            LocalDateTime receivedAt,
            List<MergeReceiptSourceResponse> sources,
            LocalDateTime createdAt) {
    }
}
