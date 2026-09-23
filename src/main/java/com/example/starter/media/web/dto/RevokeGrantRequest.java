package com.example.starter.media.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销授权请求。expectedVersionNo 用于乐观确认当前版本，可空表示不校验。
 */
public record RevokeGrantRequest(
        @NotBlank String requestId,
        Integer expectedVersionNo
) {
}
