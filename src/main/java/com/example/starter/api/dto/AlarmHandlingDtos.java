package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 告警处置相关请求/响应。处置进行中（IN_PROGRESS）时，同分区新策略下发须与其管控窗口兼容，
 * 否则下发挂起等待处置完结。发起与完结均以 requestId 幂等。
 */
public final class AlarmHandlingDtos {

    private AlarmHandlingDtos() {
    }

    /**
     * 发起告警处置：针对一条 ACTIVE 偏差告警建立临时管控窗口。
     * 温度窗口单位摄氏度、湿度窗口单位 %RH，边界均含等号。
     */
    public record StartRequest(
            @NotBlank @Size(max = 64) String handlingCode,
            @NotNull Long alarmId,
            @NotBlank @Size(max = 96) String requestId,
            @NotNull BigDecimal tempMin,
            @NotNull BigDecimal tempMax,
            @NotNull BigDecimal humidityMin,
            @NotNull BigDecimal humidityMax) {
    }

    /** 完结处置：处置完结后不再参与新策略下发的兼容性仲裁。 */
    public record CompleteRequest(
            @NotBlank @Size(max = 96) String requestId) {
    }

    public record HandlingResponse(
            String handlingCode,
            Long alarmId,
            String zoneCode,
            String status,
            BigDecimal tempMin,
            BigDecimal tempMax,
            BigDecimal humidityMin,
            BigDecimal humidityMax,
            String requestId,
            String completeRequestId,
            Instant createdAt,
            Instant completedAt) {
    }
}
