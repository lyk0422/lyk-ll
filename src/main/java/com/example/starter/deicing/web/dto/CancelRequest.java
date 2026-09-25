package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消请求体。
 */
public record CancelRequest(@NotBlank String requestId) {
}
