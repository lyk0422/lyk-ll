package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 单人撤人到位上报（非幂等动作，重复上报同一人取最早到位时刻）。
 */
public record ArrivalReportRequest(
        @NotBlank String chainNo,
        @NotBlank String personId,
        @NotNull LocalDateTime arrivedAt) {
}
