package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 方案审核请求。
 */
public record ReviewRequest(
        @NotNull ReviewDecision decision,
        String reason) {
}
