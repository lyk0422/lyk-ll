package com.example.starter.ctlog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 追加叶请求。
 *
 * @param requestId 客户端幂等键
 * @param data      合成 DER 字节的 Base64 编码；日志对其取 SHA-256 作为叶数据
 */
public record AddLeafRequest(
        @NotBlank String requestId,
        @NotNull String data) {
}
