package com.example.starter.pest.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 写操作请求 DTO；requestId 为幂等请求号，必填。
 */
public final class Requests {

    private Requests() {
    }

    /**
     * 虫情上报请求。
     */
    public record ReportRequest(
            @NotBlank String requestId,
            @NotBlank String zoneCode,
            String description,
            @NotBlank String reporter) {
    }

    /**
     * 虫情确认请求。
     */
    public record ConfirmRequest(
            @NotBlank String requestId,
            @NotBlank String confirmer) {
    }

    /**
     * 出库登记请求。
     */
    public record OutboundRequest(
            @NotBlank String requestId,
            @NotBlank String zoneCode,
            @NotBlank String item) {
    }

    /**
     * 治理投放请求（须双人登记）。
     */
    public record DispenseRequest(
            @NotBlank String requestId,
            @NotNull Long pesticideId,
            @NotNull @Positive BigDecimal dosage,
            @NotBlank String points,
            String registrar1,
            String registrar2) {
    }

    /**
     * 新增虫迹登记请求。
     */
    public record TraceRequest(
            @NotBlank String requestId,
            @NotBlank String recorder) {
    }

    /**
     * 解除申请请求。
     */
    public record LiftApplyRequest(
            @NotBlank String requestId,
            @NotBlank String applicant) {
    }

    /**
     * 解除确认请求；side 仅允许 DISINFECTION（消杀方）或 WAREHOUSE（库房方）。
     */
    public record LiftConfirmRequest(
            @NotBlank String requestId,
            @NotBlank @Pattern(regexp = "DISINFECTION|WAREHOUSE") String side,
            @NotBlank String confirmer) {
    }
}
