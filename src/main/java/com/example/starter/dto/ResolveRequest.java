package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/** 差异核销请求。 */
public record ResolveRequest(
        @NotBlank String resolution,
        String resolvedBy,
        String remark) {
}
