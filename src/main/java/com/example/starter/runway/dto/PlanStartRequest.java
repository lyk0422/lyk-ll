package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检开始请求：须重新确认日历版本，且跑道无进行中紧急关闭事件。
 *
 * @param requestId                幂等请求标识
 * @param expectedVersion          期望的计划乐观锁版本
 * @param confirmedCalendarVersion 重新确认的航班日历版本（须等于当前日历版本）
 * @param startedAt                实际开始时间（UTC），须落在窗口内且巡检时长完整在窗口内
 */
public record PlanStartRequest(
        @NotBlank String requestId,
        @Positive long expectedVersion,
        @Positive int confirmedCalendarVersion,
        @NotNull Instant startedAt) {
}
