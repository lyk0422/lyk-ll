package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 紧急改线申请：为运单申请一条新路线核准；核准通过前原路线打卡继续有效。
 */
public record RerouteRequest(@NotBlank String requestId, @NotBlank String routeCode) {
}
