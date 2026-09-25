package com.example.starter.weigh;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 过磅请求：生成检测记录，requestId 幂等。
 */
public record WeighRecordRequest(
        @NotBlank String requestId,
        @NotBlank String plateNo,
        @NotBlank String stationId,
        @NotNull Instant weighedAt,
        @NotBlank String axleType,
        @NotBlank @Pattern(regexp = "DYNAMIC|STATIC", message = "must be DYNAMIC or STATIC") String weighType,
        @Positive int totalWeightKg) {
}
