package com.example.starter.transition.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 归档请求；归档为终态，重复归档返回幂等成功。
 *
 * @param requestId 幂等键
 * @param orderNo   改品种单号
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo
) {
}
