package com.example.starter.fermentation.api;

import com.example.starter.fermentation.domain.YeastStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合成酵母单元受控投酵与回收追溯 API 的请求/响应 DTO。
 *
 * <p>写请求均携带 requestId（幂等键）；变更类请求携带 expectedVersion 做乐观并发控制，
 * 版本从 1 开始，成功变更加 1。时间字段为业务发生时间（本地时间，ISO-8601）。
 */
public final class YeastDtos {

    private YeastDtos() {
    }

    /** 登记合成酵母单元请求（初始代次 0、可用）。 */
    public record RegisterYeastRequest(
            @NotBlank String requestId,
            @NotBlank String unitCode) {
    }

    /** 受控投酵请求：批次启动主酵时必须指定酵母单元并携带期望版本。 */
    public record PitchYeastRequest(
            @NotBlank String requestId,
            @NotBlank String yeastUnitCode,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull LocalDateTime startedAt) {
    }

    /** 酵母回收请求：仅在绑定批次完成主酵后受理。 */
    public record RecoverYeastRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) Integer expectedVersion,
            @NotNull LocalDateTime recoveredAt,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
            @Digits(integer = 3, fraction = 2) BigDecimal viabilityPercent,
            @NotNull Boolean contaminated) {
    }

    /** 酵母单元响应。 */
    public record YeastUnitResponse(
            String unitCode,
            int generation,
            YeastStatus status,
            LocalDateTime lastRecoveredAt,
            Long occupiedByBatchId,
            int version) {
    }

    /** 受控投酵响应：批次信息与投酵后酵母单元信息。 */
    public record PitchResponse(
            long batchId,
            String batchNo,
            String batchStatus,
            String yeastUnitCode,
            int generation,
            YeastStatus yeastStatus,
            int yeastVersion) {
    }

    /** 酵母回收响应。 */
    public record RecoveryResponse(
            long recoveryId,
            String yeastUnitCode,
            long batchId,
            LocalDateTime recoveredAt,
            BigDecimal viabilityPercent,
            boolean contaminated,
            boolean qualified,
            int generationAfter,
            YeastStatus yeastStatus,
            int yeastVersion) {
    }
}
