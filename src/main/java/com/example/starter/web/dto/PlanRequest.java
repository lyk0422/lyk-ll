package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 减压方案生成/变更请求。
 *
 * @param requestId     本次出方案操作的幂等键
 * @param diveRequestId 所属潜次（作业申请）的 requestId
 * @param depthM        方案深度，米
 * @param bottomMin     方案底时间，分钟（不得超过该深度免减压极限，恰在极限合法）
 */
public record PlanRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @Positive int depthM,
        @Positive int bottomMin) {
}
