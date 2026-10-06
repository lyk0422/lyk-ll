package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.starter.domain.ReviewConclusion;

/**
 * 初查处置请求。
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotBlank String reviewerId,
        @NotNull ReviewConclusion conclusion) {
}
