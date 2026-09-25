package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 作业开始请求体。
 */
public record StartJobRequest(@NotBlank String requestId) {
}
