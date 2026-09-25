package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 双岗复核签字请求。
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotBlank String role,
        @NotBlank String reviewer
) {
}
