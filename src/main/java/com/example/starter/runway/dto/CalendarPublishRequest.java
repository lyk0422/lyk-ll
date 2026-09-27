package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 航班日历发布请求：版本号由服务端从1开始递增；发布成功即冻结新版本，
 * 并使冻结旧版本、尚未开始的例行计划进入待重审。requestId 幂等。
 *
 * @param requestId   幂等请求标识
 * @param publishedAt 版本发布时刻（UTC，精确时刻参与指纹）
 * @param flights     该版本全部合成航班占用窗集合（允许空集合，但必须显式提供；完整集合参与指纹）
 */
public record CalendarPublishRequest(
        @NotBlank String requestId,
        @NotNull Instant publishedAt,
        @NotNull @Valid List<FlightWindowInput> flights) {
}
