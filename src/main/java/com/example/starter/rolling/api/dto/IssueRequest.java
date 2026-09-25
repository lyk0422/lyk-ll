package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 下达请求。
 */
public record IssueRequest(@NotBlank String requestId) {
}
