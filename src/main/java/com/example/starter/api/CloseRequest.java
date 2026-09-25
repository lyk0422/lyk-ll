package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 结案请求：closedAt 为结案操作时刻（UTC 纪元秒），须不早于观察期满时刻且无症状。
public record CloseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String operationNo,
        @NotNull Long closedAt) {
}
