package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 复工评估结论（幂等）。须现场核查(siteConfirmed)与监测复核(monitorConfirmed)双确认同时通过；
 * 双确认通过才解除封锁并复工，任一不通过则驳回、维持停产封锁。
 */
public record ResumeDecisionRequest(
        @NotBlank String requestId,
        @NotBlank String chainNo,
        @NotNull LocalDateTime decideTime,
        @NotNull Boolean siteConfirmed,
        @NotNull Boolean monitorConfirmed,
        String remark) {
}
