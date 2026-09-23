package com.example.starter.calibration.api;

import java.util.List;

/**
 * 发起灰度发布请求：显式指定车辆集合，批量原子切换。
 */
public record CanaryReleaseRequest(
        long canaryVersionId,
        List<String> vehicleIds,
        String operator) {
}
