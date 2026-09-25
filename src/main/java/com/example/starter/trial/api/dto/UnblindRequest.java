package com.example.starter.trial.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 应急揭盲申请：两名不同授权人在同一事务内先后确认。
 */
public record UnblindRequest(
        @NotBlank String requestId,
        @NotBlank String firstConfirmer,
        @NotBlank String secondConfirmer) {
}
