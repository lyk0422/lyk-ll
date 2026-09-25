package com.example.starter.quarantine.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 启运报告：出证后启运，单据进入“调运在途”。requestId 为幂等键。
 */
public record DepartRequest(@NotBlank String requestId) {
}
