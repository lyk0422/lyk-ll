package com.example.starter.fermentation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合批接收服务请求/响应 DTO。写请求均携带 requestId 作为幂等键；
 * 容量与升数单位为升，正数、最多三位小数。
 */
public final class ReceivingDtos {

    private ReceivingDtos() {
    }

    /** 登记合成接收罐请求（正数容量升数）。 */
    public record RegisterTankRequest(
            @NotBlank String requestId,
            @NotBlank String tankCode,
            @NotNull @DecimalMin(value = "0.001", message = "容量必须为正数")
            @Digits(integer = 7, fraction = 3, message = "容量最多三位小数") BigDecimal capacityLiters) {
    }

    /** 调整接收罐容量请求：不得缩到已接收量以下；expectedVersion 旧版返回 409。 */
    public record UpdateTankCapacityRequest(
            @NotBlank String requestId,
            @NotNull @DecimalMin(value = "0.001", message = "容量必须为正数")
            @Digits(integer = 7, fraction = 3, message = "容量最多三位小数") BigDecimal capacityLiters,
            @NotNull Integer expectedVersion) {
    }

    /** 源批实际清酒量登记请求（一次，登记后冻结）。 */
    public record RegisterSourceLiquorRequest(
            @NotBlank String requestId,
            @NotNull @DecimalMin(value = "0.001", message = "实际清酒量必须为正数")
            @Digits(integer = 7, fraction = 3, message = "实际清酒量最多三位小数") BigDecimal liters) {
    }

    /** 合批接收请求：2 至 5 个不同受控源批合入同一接收罐，完整接收不做部分转出。 */
    public record MergeReceiveRequest(
            @NotBlank String requestId,
            @NotBlank String receivingTankCode,
            @NotEmpty @Size(min = 2, max = 5, message = "一次接收必须引用 2 至 5 个源批")
            @Valid List<@NotNull Long> batchIds,
            @NotNull Integer expectedVersion,
            @NotNull LocalDateTime receivedAt) {
    }

    /** 接收罐响应。 */
    public record ReceivingTankResponse(
            long id,
            String tankCode,
            BigDecimal capacityLiters,
            BigDecimal receivedLiters,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 源批清酒量台账响应。 */
    public record SourceLiquorResponse(
            long id,
            long batchId,
            String batchNo,
            BigDecimal liters,
            boolean frozen,
            int version,
            LocalDateTime createdAt) {
    }

    /** 合批接收来源明细响应。 */
    public record MergeItemResponse(
            long batchId,
            String batchNo,
            int curveVersion,
            BigDecimal liters) {
    }

    /** 合批接收受理响应。 */
    public record MergeReceiptResponse(
            long receiptId,
            String receivingTankCode,
            BigDecimal totalLiters,
            BigDecimal receivedLitersAfter,
            int tankVersion,
            LocalDateTime receivedAt,
            List<MergeItemResponse> items) {
    }
}
