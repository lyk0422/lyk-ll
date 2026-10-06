package com.example.starter.api.dto;

import com.example.starter.domain.ReopenRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复开双岗确认请求。
 */
public record ReopenConfirmationRequest(@NotBlank String requestId, @NotNull ReopenRole role) {
}
