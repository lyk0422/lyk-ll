package com.example.starter.ventilation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 火警解除复位请求。
 *
 * @param requestId      复位指令自身的幂等键
 * @param modeRequestId  待复位火灾模式的激活 requestId
 */
public record ResetRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String modeRequestId) {
}
