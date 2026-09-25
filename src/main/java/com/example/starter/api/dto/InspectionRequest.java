package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 自检互检签署请求：自检人与互检人必须不同。
 */
public record InspectionRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotBlank String selfInspector,
        @NotBlank String mutualInspector) {
}
