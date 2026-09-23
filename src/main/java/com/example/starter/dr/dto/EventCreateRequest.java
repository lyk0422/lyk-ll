package com.example.starter.dr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 创建需求响应事件请求。时间统一使用 UTC（如 2026-09-23T01:00:00Z）。
 *
 * @param eventRef        事件业务编码，全局唯一
 * @param startUtc        事件起始时刻 UTC（含）
 * @param endUtc          事件结束时刻 UTC（不含），必须晚于起始时刻
 * @param intervalMinutes 固定区间粒度（分钟），区间长度必须为其整数倍
 */
public record EventCreateRequest(
        @NotBlank String eventRef,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer intervalMinutes) {
}
