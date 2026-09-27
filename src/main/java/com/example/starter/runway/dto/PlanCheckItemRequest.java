package com.example.starter.runway.dto;

import com.example.starter.runway.CheckResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 巡检检查项回报请求：仅 RUNNING 计划受理；中止后收到的检查回报不新增检查项（作为迟到语义拒绝登记）。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的计划聚合版本（乐观并发控制）
 * @param itemCode        检查项编码（合成）
 * @param result          检查结果：PASS/FAIL
 * @param remark          备注（可空，合法文本可含任意分隔符）
 * @param observedAt      现场观测精确时刻（UTC）
 */
public record PlanCheckItemRequest(
        @NotBlank String requestId,
        int expectedVersion,
        @NotBlank String itemCode,
        @NotNull CheckResult result,
        @Size(max = 255) String remark,
        @NotNull Instant observedAt) {
}
