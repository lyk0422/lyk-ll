package com.example.starter.ili.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 对齐批次导入请求。
 *
 * @param requestId 客户端幂等键
 * @param segmentId 管段标识
 * @param oldRun    旧检测回合
 * @param newRun    新检测回合
 */
public record ImportBatchRequest(
        @NotBlank String requestId,
        @NotBlank String segmentId,
        @NotNull @Valid RunPayload oldRun,
        @NotNull @Valid RunPayload newRun) {
}
