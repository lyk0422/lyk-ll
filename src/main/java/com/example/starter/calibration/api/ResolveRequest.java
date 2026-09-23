package com.example.starter.calibration.api;

import java.time.Instant;

/**
 * 参数解析请求。{@code at} 为 null 时使用服务当前时间（UTC）。
 */
public record ResolveRequest(
        String paramKey,
        String vehicleId,
        String sensorId,
        String mapVersion,
        Instant at) {
}
