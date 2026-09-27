package com.example.starter.fermentation.api;

import com.example.starter.fermentation.domain.YeastStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合成酵母单元登记、受控投酵与回收追溯的请求/响应 DTO。
 * 写请求均携带 requestId 幂等键；修改类请求携带 expectedVersion 乐观版本（从 1 开始）。
 * 时间字段为业务发生时间（本地时间，ISO-8601）。
 */
public final class YeastDtos {

    private YeastDtos() {
    }

    /** 酵母单元登记请求：登记后代次 0、状态 AVAILABLE、版本 1，无回收时刻。 */
    public record RegisterYeastRequest(
            @NotBlank String requestId,
            @NotBlank String unitCode) {
    }

    /**
     * 受控投酵请求：批次启动主酵时必须指定酵母并原子绑定。
     *
     * @param requestId       幂等键
     * @param yeastUnitCode   酵母单元唯一编号
     * @param expectedVersion 酵母单元当前乐观版本，不匹配返回 409
     * @param pitchedAt       投酵（主酵开始）业务时刻
     */
    public record PitchYeastRequest(
            @NotBlank String requestId,
            @NotBlank String yeastUnitCode,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull LocalDateTime pitchedAt) {
    }

    /**
     * 酵母回收请求：仅在绑定批次完成主酵后受理。
     *
     * @param requestId        幂等键
     * @param expectedVersion  酵母单元当前乐观版本，不匹配返回 409
     * @param recoveredAt      回收受理的业务时刻
     * @param viabilityPercent 活率（百分比）：恰 90.00 合格，低于 90 报废
     * @param contaminated     污染判定：true 有污染即报废
     */
    public record YeastRecoveryRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull LocalDateTime recoveredAt,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
            @Digits(integer = 3, fraction = 2) BigDecimal viabilityPercent,
            @NotNull Boolean contaminated) {
    }

    /** 酵母单元响应。status 为库内状态；effectiveStatus 叠加按当前时钟计算的 72 小时失效。 */
    public record YeastUnitResponse(
            long id,
            String unitCode,
            int generation,
            YeastStatus status,
            YeastStatus effectiveStatus,
            boolean expired,
            LocalDateTime lastRecoveredAt,
            Long occupiedByBatchId,
            int version,
            LocalDateTime createdAt) {
    }

    /** 酵母回收记录响应（追溯用）。 */
    public record YeastRecoveryResponse(
            long id,
            String yeastUnitCode,
            long batchId,
            BigDecimal viabilityPercent,
            boolean contaminated,
            boolean qualified,
            int generationAfter,
            int unitVersion,
            LocalDateTime recoveredAt,
            LocalDateTime createdAt) {
    }
}
