package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轧制实绩回传请求，按“合同号＋道次”去重。
 */
public record ActualResultRequest(
        @NotBlank String requestId,
        @NotBlank String lineCode,
        @NotBlank String contractNo,
        @NotNull Integer passNo,
        @NotNull @Positive BigDecimal rollingKm,
        @NotNull LocalDateTime recordedAt) {
}
