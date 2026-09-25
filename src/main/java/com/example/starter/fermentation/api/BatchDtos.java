package com.example.starter.fermentation.api;

import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.CurvePhase;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发酵批次服务请求/响应 DTO。所有请求均携带 requestId 作为幂等键；
 * 时间字段为业务发生时间（本地时间，ISO-8601，如 2026-09-26T10:00:00）。
 */
public final class BatchDtos {

    private BatchDtos() {
    }

    /** 创建批次（满罐）请求。 */
    public record CreateBatchRequest(
            @NotBlank String requestId,
            @NotBlank String batchNo,
            @NotBlank String tankCode,
            @NotBlank String curveCode,
            @NotNull LocalDateTime filledAt) {
    }

    /** 阶段开始请求（主酵开始 / 后熟开始）。 */
    public record StartPhaseRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime startedAt) {
    }

    /** 温度采样上报请求。 */
    public record TemperatureReadingRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime sampledAt,
            @NotNull @Digits(integer = 3, fraction = 2) BigDecimal temperature) {
    }

    /** 双乙酰检测上报请求。 */
    public record DiacetylTestRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime testedAt,
            @NotNull @DecimalMin("0.000") @Digits(integer = 2, fraction = 3) BigDecimal valueMgPerLiter) {
    }

    /** 降温指令请求。 */
    public record CoolingRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime instructedAt) {
    }

    /** 延长后熟请求。 */
    public record ExtendMaturationRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime extendedAt) {
    }

    /** 成熟度判定请求。 */
    public record JudgeRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime judgedAt) {
    }

    /** 清酒转出请求。 */
    public record TransferRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime transferredAt) {
    }

    /** 批次响应。 */
    public record BatchResponse(
            long id,
            String batchNo,
            BatchStatus status,
            String curveCode,
            int curveVersion,
            boolean maturationExtended,
            LocalDateTime filledAt,
            LocalDateTime mainFermentStartedAt,
            LocalDateTime coolingStartedAt,
            LocalDateTime maturationStartedAt,
            LocalDateTime judgedAt,
            LocalDateTime transferredAt) {
    }

    /** 温度采样响应。 */
    public record ReadingResponse(
            long id,
            long batchId,
            CurvePhase phase,
            int curveVersion,
            LocalDateTime sampledAt,
            BigDecimal temperature,
            BigDecimal setpoint,
            BigDecimal deviation,
            boolean outOfTolerance,
            int consecutiveOut,
            boolean alarmTriggered,
            boolean late) {
    }

    /** 双乙酰检测响应。 */
    public record DiacetylResponse(
            long id,
            long batchId,
            LocalDateTime testedAt,
            BigDecimal valueMgPerLiter,
            boolean late) {
    }

    /** 成熟度判定响应。 */
    public record JudgeResponse(
            boolean qualified,
            boolean canExtend,
            BatchStatus status,
            int requiredDays,
            LocalDateTime judgedAt) {
    }

    /** 迟到数据响应（批次已转出，仅记录迟到事件）。 */
    public record LateResponse(
            boolean late,
            String eventType,
            long batchId) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(
            String error,
            String message) {
    }
}
