package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检开始请求：重新确认日历版本且跑道无进行中紧急关闭；expectedVersion 乐观并发。requestId 幂等。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 客户端持有的计划版本号（从1开始，不匹配返回409）
 * @param calendarVersion 开始时重新确认的航班日历版本号（须等于计划冻结版本且为最新版本）
 * @param startedAt       巡检实际开始时刻（UTC，精确时刻参与指纹）
 */
public record PlanStartRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @Positive int calendarVersion,
        @NotNull Instant startedAt) {
}
