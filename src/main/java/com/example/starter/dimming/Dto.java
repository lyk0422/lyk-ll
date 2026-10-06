package com.example.starter.dimming;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口出入参定义。所有写操作均携带 requestId 参与幂等。
 */
public final class Dto {

    private Dto() {
    }

    /** 调光指令请求：targetLevel 目标档位，source 触发来源 */
    public record DimCommandRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotNull @Min(0) @Max(5) Integer targetLevel,
            @NotBlank String source) {
    }

    /** 调光指令结果 */
    public record DimCommandResponse(
            String requestId,
            String circuitCode,
            String source,
            int fromLevel,
            int toLevel,
            boolean changed,
            LocalDateTime adjustedAt) {
    }

    /** 合成检测数据上报：洞外亮度（lux）与车流量 */
    public record TelemetryRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotNull Double brightness,
            @NotNull @Min(0) Integer trafficFlow) {
    }

    /** 检测数据评估结果：action 为 NONE/BRIGHTNESS_RAISE/TRAFFIC_FLOOR/LOCKED_SKIP */
    public record TelemetryResponse(
            String requestId,
            String circuitCode,
            String action,
            String reason,
            int currentLevel,
            boolean locked) {
    }

    /** 事故锁定请求 */
    public record AccidentLockRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 64) String alarmId,
            @Size(max = 128) String detail) {
    }

    /** 事故解除请求：onSiteConfirmed 必须为 true（现场确认） */
    public record AccidentUnlockRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotNull Boolean onSiteConfirmed,
            @Size(max = 128) String detail) {
    }

    /** 事故锁定/解除结果 */
    public record AccidentResponse(
            String requestId,
            String circuitCode,
            String action,
            boolean locked,
            int currentLevel,
            LocalDateTime operatedAt) {
    }

    /** 回路状态 */
    public record CircuitStateResponse(
            String code,
            String name,
            int currentLevel,
            boolean locked,
            LocalDateTime lastAdjustedAt,
            LocalDateTime transitionUntil) {
    }

    /** 日结能耗分段：可复算，能耗=Σ level×hours×每档功率 */
    public record SettlementSegment(
            LocalDateTime from,
            LocalDateTime to,
            int level,
            double hours,
            double energyKwh) {
    }

    /** 单回路日结 */
    public record CircuitSettlement(
            String circuitCode,
            int dimCount,
            double energyKwh,
            List<SettlementSegment> segments) {
    }

    /** 日结输出 */
    public record DailySettlementResponse(
            String date,
            List<CircuitSettlement> circuits) {
    }
}
