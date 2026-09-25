package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 宰前检疫请求。
 *
 * @param requestId 幂等请求标识
 * @param inspector 检疫人员（合成数据）
 */
public record PreQuarantineRequest(
        @NotBlank String requestId,
        @NotBlank String inspector) {
}
