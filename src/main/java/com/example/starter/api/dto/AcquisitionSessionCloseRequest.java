package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 关闭采集会话请求：必须无缺号且全部缓冲已消费，否则拒绝。
 *
 * @param expectedVersion 会话乐观版本（从 1 开始，成功变更加 1）
 */
public record AcquisitionSessionCloseRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
