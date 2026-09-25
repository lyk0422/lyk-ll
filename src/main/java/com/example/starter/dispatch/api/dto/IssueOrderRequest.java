package com.example.starter.dispatch.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 调度令下达请求。
 *
 * @param requestId 幂等键，调用方生成；同键同参返回首次结果，同键异参 409
 * @param gates     闸门目标列表，顺序即多闸门令的执行顺序
 * @param deadline  执行时限（UTC），逾期未收齐回执自动超时升级
 */
public record IssueOrderRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<GateTarget> gates,
        @NotNull Instant deadline) {
}
