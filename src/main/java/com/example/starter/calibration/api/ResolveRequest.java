package com.example.starter.calibration.api;

/**
 * 参数解析请求：按车辆、传感器、地图版本和时间匹配。
 *
 * @param at 解析时间（UTC），null 表示当前时间
 */
public record ResolveRequest(
        String paramKey,
        String vehicleId,
        String sensorId,
        String mapVersion,
        java.time.Instant at
) {
}
