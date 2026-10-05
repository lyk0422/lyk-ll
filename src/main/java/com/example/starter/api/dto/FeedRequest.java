package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 投料请求（以 requestId 幂等）。与升温曲线状态在同一事务内校验，曲线未走完禁止投料。
 *
 * @param requestId  投料幂等键
 * @param kilnId     窑编号
 * @param operatorId 操作人编号
 */
public record FeedRequest(
        @NotBlank String requestId,
        @NotBlank String kilnId,
        @NotBlank String operatorId) {
}
