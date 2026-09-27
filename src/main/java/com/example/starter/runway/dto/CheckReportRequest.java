package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 例行巡检检查回报请求。被抢占（取消/中止）后的回报仅登记迟到，不开放跑道。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的计划乐观锁版本
 * @param reportedAt      回报时间（UTC）
 * @param completedItems  本次回报已完成检查项
 * @param complete        是否完结回报：真=巡检正常完成，仅关闭计划
 */
public record CheckReportRequest(
        @NotBlank String requestId,
        @Positive long expectedVersion,
        @NotNull Instant reportedAt,
        @NotNull List<@NotBlank String> completedItems,
        boolean complete) {
}
