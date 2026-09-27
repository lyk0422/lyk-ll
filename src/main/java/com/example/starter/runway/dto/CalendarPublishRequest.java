package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

/**
 * 航班日历发布请求：携带完整占用窗集合与精确时刻，成功后日历版本加1（首次发布 expectedVersion=0，结果版本为1）。
 * requestId 幂等：同键同参重放首次冻结结果，同键异参 409，失败不占键。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的当前日历版本；0 表示日历尚不存在（首次发布）
 * @param publishedAt     发布精确时刻（UTC）
 * @param windows         该版本的完整合成航班占用窗集合（不得为空集合；无航班请显式使用无冲突的合法窗口设计，至少一项）
 */
public record CalendarPublishRequest(
        @NotBlank String requestId,
        @PositiveOrZero int expectedVersion,
        @NotNull Instant publishedAt,
        @NotEmpty @Valid List<FlightOccupancyInput> windows) {
}
