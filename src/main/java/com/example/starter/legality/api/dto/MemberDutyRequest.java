package com.example.starter.legality.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 单个机组成员的值勤提交；时刻均为带偏移的 ISO-8601（建议 UTC，如 2026-09-20T06:00:00Z）。
 *
 * @param requestId  客户端幂等键，指纹覆盖该字段
 * @param reportTime 报到时刻，FDP 起算点
 * @param plannedEnd 计划执勤结束时刻（不含延误延长）
 */
public record MemberDutyRequest(
        @NotBlank(message = "requestId 不能为空")
        String requestId,
        @NotBlank(message = "memberCode 不能为空")
        String memberCode,
        @NotBlank(message = "flightNo 不能为空")
        String flightNo,
        @NotNull(message = "reportTime 不能为空")
        Instant reportTime,
        @NotNull(message = "plannedEnd 不能为空")
        Instant plannedEnd
) {
}
