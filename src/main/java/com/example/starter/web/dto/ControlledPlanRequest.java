package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.Map;

/**
 * 反复潜水积分受控方案请求（虚构积分模型，不用于真实潜水减压）。
 *
 * @param requestId       本次受控方案操作的幂等键
 * @param diveRequestId   所属潜次（作业申请）的 requestId
 * @param depthM          方案深度，米（须在合成免减压表内）
 * @param bottomMin       计划整数水下分钟数（恰用尽两人较短允许分钟数合法）
 * @param at              方案计算的精确时刻（UTC），按此派生当前积分与水面间隔
 * @param expectedVersions 每名组员期望的积分账户版本集合：工号 -&gt; 版本（首次潜账户尚未建立时期望为 1）
 */
public record ControlledPlanRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @Positive int depthM,
        @Positive int bottomMin,
        @NotNull Instant at,
        @NotNull Map<String, Integer> expectedVersions) {
}
