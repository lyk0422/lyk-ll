package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 出水登记请求：观察期 7200 秒，时刻为 UTC 纪元秒。
public record ExitRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String operationNo,
        @NotNull Long exitAt) {
}
