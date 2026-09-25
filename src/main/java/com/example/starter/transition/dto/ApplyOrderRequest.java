package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 改品种申请请求。
 *
 * @param requestId 幂等键
 */
public record ApplyOrderRequest(
        @NotBlank String requestId,
        @NotBlank String paperMachine,
        @NotBlank String fromGrade,
        @NotBlank String toGrade) {
}
