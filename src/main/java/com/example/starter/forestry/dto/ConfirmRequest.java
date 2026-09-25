package com.example.starter.forestry.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 采伐确认请求（以 requestId 幂等）。采伐完成时刻取服务端可控时钟（UTC）。
 *
 * @param requestId 幂等键
 * @param licenseId 采伐许可证 ID
 */
public record ConfirmRequest(@NotBlank String requestId, @NotBlank String licenseId) {
}
