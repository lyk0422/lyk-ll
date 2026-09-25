package com.example.starter.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 气候策略相关请求/响应。
 */
public final class StrategyDtos {

    private StrategyDtos() {
    }

    /**
     * 编制并校验气候策略：温湿度设定点必须落在作物生育期允许窗口内（边界合法），
     * 昼夜设定差绝对值不得超过 8 摄氏度（恰 8 合法）。
     */
    public record DraftRequest(
            @NotBlank @Size(max = 64) String strategyCode,
            @NotBlank @Size(max = 64) String zoneCode,
            @NotBlank @Size(max = 64) String cropCode,
            @NotBlank @Size(max = 64) String stageCode,
            @NotNull BigDecimal dayTemp,
            @NotNull BigDecimal nightTemp,
            @NotNull BigDecimal humiditySetpoint,
            @NotNull @DecimalMin("0.0") BigDecimal tempTolerance,
            @NotNull @DecimalMin("0.0") BigDecimal humidityTolerance) {
    }

    /**
     * 下发策略：effectiveTime 为生效时刻（UTC），与同分区上一策略生效时刻间隔须不少于 30 分钟。
     */
    public record DispatchRequest(
            @NotBlank @Size(max = 96) String requestId,
            @NotNull Instant effectiveTime) {
    }

    public record StrategyResponse(
            String strategyCode,
            String zoneCode,
            String cropCode,
            String stageCode,
            BigDecimal dayTemp,
            BigDecimal nightTemp,
            BigDecimal humiditySetpoint,
            BigDecimal tempTolerance,
            BigDecimal humidityTolerance,
            String status,
            String requestId,
            Instant effectiveTime,
            Instant createdAt,
            Instant dispatchedAt,
            Instant expiredAt) {
    }

    /** 昼夜设定差等校验结果明细，供编制失败时定位规则。 */
    public record ValidationResult(boolean valid, String message) {
    }
}
