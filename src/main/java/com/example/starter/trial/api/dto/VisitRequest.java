package com.example.starter.trial.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 访视记录登记（合成数据），用于验证揭盲后访视排除标记。
 */
public record VisitRequest(
        @NotBlank String visitCode,
        @NotNull Instant visitTimeUtc) {
}
