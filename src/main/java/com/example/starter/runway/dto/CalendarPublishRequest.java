package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 航班日历发布请求：成功发布产生新版本 expectedVersion+1。requestId 幂等。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 客户端所见当前最新版本（从1开始）；与库内不符返回 409
 * @param publishedAt     新版本精确生效时刻（UTC）
 * @param occupancies     该版本携带的合成航班占用窗集合（可为空列表；全量替换语义）
 */
public record CalendarPublishRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull Instant publishedAt,
        @NotNull @Valid List<@Valid FlightWindowInput> occupancies) {
}
