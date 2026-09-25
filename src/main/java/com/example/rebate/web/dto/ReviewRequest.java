package com.example.rebate.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 疑点复核确认请求。仅 {@code confirmed=true} 可解除疑点。
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotNull @AssertTrue Boolean confirmed) {
}
