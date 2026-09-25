package com.example.starter.fermentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 成熟度综合判定请求，requestId 幂等。
 */
public record JudgeRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull Instant judgedAt) {
}
