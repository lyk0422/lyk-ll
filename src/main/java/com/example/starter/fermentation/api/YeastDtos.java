package com.example.starter.fermentation.api;

import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.RecycleResult;
import com.example.starter.fermentation.domain.YeastUnitStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酵母代次限用与投酵回收追溯 API 的请求/响应 DTO。
 * 所有写请求携带 requestId 作为幂等键；修改操作携带 expectedVersion 乐观锁版本（从 1 开始）。
 * 时间字段为业务发生时间（本地时间，ISO-8601，如 2026-09-26T10:00:00）。
 */
public final class YeastDtos {

    private YeastDtos() {
    }

    /** 登记酵母单元请求：初始代次 0、可用、版本 1。 */
    public record RegisterYeastRequest(
            @NotBlank String requestId,
            @NotBlank String yeastCode) {
    }

    /** 受控启动主酵请求：必须指定酵母并携带其当前版本。 */
    public record ControlledStartRequest(
            @NotBlank String requestId,
            @NotBlank String yeastCode,
            @NotNull Integer expectedVersion,
            @NotNull LocalDateTime startedAt) {
    }

    /** 酵母回收请求：保存活率与污染判定，携带酵母当前版本。 */
    public record RecycleRequest(
            @NotBlank String requestId,
            @NotNull Long batchId,
            @NotNull Integer expectedVersion,
            @NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal viabilityPercent,
            boolean contaminated,
            @NotNull LocalDateTime recoveredAt) {
    }

    /** 酵母单元响应。 */
    public record YeastUnitResponse(
            long id,
            String yeastCode,
            int generation,
            YeastUnitStatus status,
            int version,
            LocalDateTime lastRecoveredAt,
            Long activeBatchId) {
    }

    /** 受控启动主酵响应：批次状态与酵母绑定结果。 */
    public record ControlledStartResponse(
            long batchId,
            BatchStatus status,
            String yeastCode,
            int yeastGeneration,
            int yeastVersion,
            LocalDateTime startedAt) {
    }

    /** 酵母回收响应。 */
    public record RecycleResponse(
            long recordId,
            String yeastCode,
            long batchId,
            RecycleResult result,
            int generationBefore,
            int generationAfter,
            int yeastVersion,
            LocalDateTime recoveredAt) {
    }

    /** 回收追溯记录响应。 */
    public record RecycleRecordResponse(
            long id,
            long yeastUnitId,
            long batchId,
            int generationBefore,
            int generationAfter,
            BigDecimal viabilityPercent,
            boolean contaminated,
            RecycleResult result,
            LocalDateTime recoveredAt) {
    }
}
