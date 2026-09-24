package com.example.starter.portability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 仅含幂等请求标识的请求体，用于签码、携出方确认、生效广播与撤销。
 *
 * @param requestId 幂等请求标识，指纹组成部分
 */
public record RequestIdRequest(
        @NotBlank @Size(max = 64) String requestId) {
}
