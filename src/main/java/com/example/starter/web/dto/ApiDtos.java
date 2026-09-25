package com.example.starter.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API 请求与响应 DTO 定义。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 创建基站请求。 */
    public record CreateStationRequest(
            @NotBlank String requestId,
            @NotBlank String stationCode,
            @NotNull @DecimalMin("0.01") BigDecimal loadKw,
            @NotNull @DecimalMin("0.01") BigDecimal batteryCapacityKwh) {
    }

    /** 基站响应。 */
    public record StationResponse(long id, String stationCode, BigDecimal loadKw,
                                  BigDecimal batteryCapacityKwh) {
    }

    /** 创建油机请求。 */
    public record CreateGeneratorRequest(
            @NotBlank String requestId,
            @NotBlank String generatorCode) {
    }

    /** 油机响应。 */
    public record GeneratorResponse(long id, String generatorCode, String status) {
    }

    /** 电池容量读数上报请求；readingTime 为 UTC 抄表时刻。 */
    public record BatteryReadingRequest(
            @NotBlank String requestId,
            @NotBlank String stationCode,
            @NotNull java.time.Instant readingTime,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal remainingPercent) {
    }

    /** 读数响应；deduplicated=true 表示按“基站＋抄表时刻”命中已有读数。 */
    public record BatteryReadingResponse(long id, String stationCode, java.time.Instant readingTime,
                                         BigDecimal remainingPercent, boolean deduplicated) {
    }

    /** 市电中断告警请求；outageStartTime 为 UTC 停电起始时刻。 */
    public record MainsFailureAlarmRequest(
            @NotBlank String requestId,
            @NotBlank String stationCode,
            @NotNull java.time.Instant outageStartTime) {
    }

    /** 告警响应；merged=true 表示合并进事件期内的既有事件，未重复派单。 */
    public record AlarmResponse(long eventId, String stationCode, java.time.Instant outageStartTime,
                                boolean merged, BigDecimal supportHours,
                                DispatchOrderView dispatchOrder) {
    }

    /** 市电恢复请求；recoveredAt 为 UTC 恢复时刻。 */
    public record MainsRecoveryRequest(
            @NotBlank String requestId,
            @NotBlank String stationCode,
            @NotNull java.time.Instant recoveredAt) {
    }

    /** 恢复响应；releasedOrderIds 为本次解除的调度单。 */
    public record RecoveryResponse(long eventId, String stationCode, boolean resolved,
                                   java.util.List<Long> releasedOrderIds) {
    }

    /** 发电确认请求（油机到位启动后回传）。 */
    public record ConfirmGenerationRequest(@NotBlank String requestId) {
    }

    /** 改派请求。 */
    public record ReassignRequest(
            @NotBlank String requestId,
            @NotBlank String generatorCode) {
    }

    /** 结案请求。 */
    public record CloseOrderRequest(@NotBlank String requestId) {
    }

    /** 调度单视图；时间均为 UTC。 */
    public record DispatchOrderView(long id, String requestId, String stationCode,
                                    String generatorCode, String status, BigDecimal supportHours,
                                    java.time.Instant deadlineAt, java.time.Instant assignedAt,
                                    java.time.Instant confirmedAt, java.time.Instant closedAt) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String error, String message) {
    }
}
