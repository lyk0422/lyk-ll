package com.example.starter.penalty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 处罚决定请求：对指定检测记录作出处罚，requestId 幂等。
 */
public record PenaltyRequest(
        @NotBlank String requestId,
        @NotNull Long weighRecordId) {
}
