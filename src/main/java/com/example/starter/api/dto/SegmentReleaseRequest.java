package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 缓停放在途吊厢分段放行请求。
 */
public record SegmentReleaseRequest(@NotBlank String requestId, @NotNull Long segmentId) {
}
