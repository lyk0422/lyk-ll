package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.BatchDtos.BatchResponse;
import com.example.starter.fermentation.domain.YeastUnitStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酵母代次限用与投酵回收追溯的请求/响应 DTO。所有写请求携带 requestId 作为幂等键，
 * 修改类请求携带 expectedVersion 做乐观锁校验；时间字段为业务发生时间（本地时间，ISO-8601）。
 */
public final class YeastDtos {

    private YeastDtos() {
    }

    /** 登记酵母单元请求。 */
    public record RegisterYeastUnitRequest(
            @NotBlank String requestId,
            @NotBlank String unitCode) {
    }

    /** 受控投酵启动主酵请求：必须指定酵母单元并携带其期望版本。 */
    public record ControlledStartRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime startedAt,
            @NotBlank String yeastUnitCode,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 酵母回收请求：携带回收时刻、活率、污染判定与期望版本。 */
    public record RecoverYeastRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime recoveredAt,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
            @Digits(integer = 3, fraction = 2) BigDecimal viabilityPercent,
            @NotNull Boolean contaminated,
            @NotNull @Min(1) Integer expectedVersion) {
    }

    /** 酵母单元响应。 */
    public record YeastUnitResponse(
            long id,
            String unitCode,
            int generation,
            YeastUnitStatus status,
            LocalDateTime lastRecoveredAt,
            int version,
            Long activeBatchId) {
    }

    /** 受控投酵启动主酵响应：批次与酵母单元在同事务内完成绑定。 */
    public record ControlledStartResponse(
            BatchResponse batch,
            YeastUnitResponse yeastUnit) {
    }

    /** 酵母回收记录响应（追溯链一环）。 */
    public record RecoveryResponse(
            long id,
            long yeastUnitId,
            long batchId,
            LocalDateTime recoveredAt,
            BigDecimal viabilityPercent,
            boolean contaminated,
            String outcome,
            int generationAfter) {
    }

    /** 酵母回收响应：回收结果与解除批次占用同事务完成。 */
    public record RecoverYeastResponse(
            YeastUnitResponse yeastUnit,
            RecoveryResponse recovery) {
    }
}
