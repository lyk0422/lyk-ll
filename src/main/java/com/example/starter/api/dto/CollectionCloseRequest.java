package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 关闭采集会话请求：必须无缺号且全部缓冲已消费，否则 422 拒绝。
 *
 * @param expectedVersion 调用方持有的会话版本，须与当前版本一致
 */
public record CollectionCloseRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotNull @Positive Integer expectedVersion) {
}
