package com.example.starter.slot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 系列退回请求。
 *
 * @param requestId 原系列申请幂等键
 */
public record ReleaseRequest(@NotBlank String requestId) {
}
