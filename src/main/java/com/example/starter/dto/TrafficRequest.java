package com.example.starter.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 车流量合成检测数据上报请求，按 5 分钟滚动更新。
 *
 * @param requestId 幂等键
 * @param flow      车流量，单位 辆/5分钟；恰等为 0 时降至保底亮度
 * @param sampledAt 采样时间(UTC)，为空时取服务端当前时间
 */
public record TrafficRequest(
        @NotBlank String requestId,
        @NotNull @Min(0) Integer flow,
        Instant sampledAt) {
}
