package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 关闭采集会话请求：必须无缺号且全部缓冲包已消费，否则拒绝。
 *
 * @param expectedVersion 会话乐观锁版本
 */
public record CollectionCloseRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotNull @Positive Integer expectedVersion) {
}
