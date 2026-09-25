package com.example.starter.ventilation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 火警解除复位请求。
 *
 * @param requestId 幂等键
 * @param zoneCode  防火分区编码
 */
public record ResetRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String zoneCode
) {
}
