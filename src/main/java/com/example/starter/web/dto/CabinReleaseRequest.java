package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 吊厢分段放行请求。
 *
 * @param requestId  幂等请求标识
 * @param operatorId 操作人标识
 */
public record CabinReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId) {
}
