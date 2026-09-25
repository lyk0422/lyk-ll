package com.example.starter.water.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 取水监管服务 API 传输对象。水量单位为立方米，时刻为 UTC（ISO-8601）。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 创建取水户请求（合成数据）。 */
    public record CreateUserRequest(
            @NotBlank String userCode,
            @NotBlank String userName,
            @NotBlank String reachCode,
            @NotNull @Positive BigDecimal annualQuota) {
    }

    public record UserResponse(Long id, String userCode, String userName, String reachCode,
                               BigDecimal annualQuota) {
    }

    /** 创建计量点请求（合成数据）。 */
    public record CreateMeterPointRequest(
            @NotBlank String pointCode,
            @NotBlank String userCode) {
    }

    public record MeterPointResponse(Long id, String pointCode, String userCode) {
    }

    /** 月度取水计划申报请求，requestId 幂等。 */
    public record DeclarationRequest(
            @NotBlank String requestId,
            @NotBlank String userCode,
            @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be yyyy-MM") String month,
            @NotNull @Positive BigDecimal plannedAmount) {
    }

    /** 申报结果：status 为 CONFIRMED（已确认）或 REJECTED（已拒绝）。 */
    public record DeclarationResponse(String requestId, String userCode, String month, String status,
                                      Integer version, BigDecimal plannedAmount, BigDecimal acceptedAmount,
                                      boolean restricted, String reason) {
    }

    /** 计量读数上报请求，按计量点+表底时刻去重。 */
    public record ReadingRequest(
            @NotBlank String meterPoint,
            @NotNull Instant readAt,
            @NotNull @PositiveOrZero BigDecimal cumulativeReading) {
    }

    public record ReadingResponse(String meterPoint, Instant readAt, BigDecimal cumulativeReading,
                                  String bucketMonth, boolean duplicate) {
    }

    /** 生态流量限取触发请求。 */
    public record RestrictionRequest(@NotBlank String reachCode) {
    }

    public record RestrictionResponse(String reachCode, boolean active, Instant effectiveAt) {
    }

    /** 年度计划封存请求。 */
    public record SealRequest(
            @NotBlank String userCode,
            @NotNull @Min(2000) @Max(2100) Integer year) {
    }

    public record SealResponse(String userCode, int year, Instant sealedAt, boolean alreadySealed) {
    }

    /** 年度额度视图：许可基数、结转扣减、有效额度、已确认申报合计、实际取水、剩余额度。 */
    public record QuotaResponse(String userCode, int year, BigDecimal baseQuota,
                                BigDecimal carryoverDeduction, BigDecimal effectiveQuota,
                                BigDecimal confirmedTotal, BigDecimal actualUsage, BigDecimal remaining) {
    }

    /** 月度实际取水量（按计量读数差分归月累计）。 */
    public record UsageResponse(String userCode, String month, BigDecimal actualUsage) {
    }

    public record ErrorResponse(String code, String message) {
    }
}
