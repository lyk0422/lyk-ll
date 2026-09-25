package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 出厂核销请求。核销为终态，重复核销返回幂等成功。
 *
 * @param requestId 幂等请求标识
 * @param operator  核销经办人（合成数据）
 */
public record ReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String operator) {
}
