package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 关闭采集会话请求：要求无缺号且全部缓冲包已消费，否则 422。
 *
 * @param expectedVersion 会话乐观版本号，须等于当前版本，否则 409
 */
public record AcquisitionSessionCloseRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotNull @Positive Integer expectedVersion) {
}
