package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 传感器读数相关请求/响应。读数按“分区＋采样时刻”去重，乱序按时刻归位。
 */
public final class ReadingDtos {

    private ReadingDtos() {
    }

    public record ReadingRequest(
            @NotBlank @Size(max = 64) String zoneCode,
            @NotNull Instant sampleTime,
            @NotNull BigDecimal temperature,
            @NotNull BigDecimal humidity) {
    }

    public record ReadingResponse(
            String zoneCode,
            Instant sampleTime,
            BigDecimal temperature,
            BigDecimal humidity,
            boolean deduplicated,
            boolean late,
            Long alarmId) {
    }
}
