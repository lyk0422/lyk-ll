package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 掺配失控恢复请求：恢复须复核确认。
 *
 * @param requestId 幂等键
 * @param reviewer  复核人
 */
public record BlendResumeRequest(
        @NotBlank String requestId,
        @NotBlank String reviewer) {
}
