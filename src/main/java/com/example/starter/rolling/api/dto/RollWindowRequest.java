package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 换辊窗口请求：半开区间 [startTime, endTime)。
 */
public record RollWindowRequest(
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {
}
