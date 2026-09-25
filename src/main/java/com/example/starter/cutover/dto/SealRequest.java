package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封板请求。封板为终态，重复封板幂等成功。
 */
public record SealRequest(
        @NotBlank String requestUid,
        @NotBlank String requestId
) {
}
