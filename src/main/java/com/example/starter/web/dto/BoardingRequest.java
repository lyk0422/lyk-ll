package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 上客请求。
 *
 * @param requestId      幂等请求标识
 * @param passengerCount 上客人数
 */
public record BoardingRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer passengerCount) {
}
