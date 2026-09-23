package com.example.starter.calibration.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 灰度发布请求：指定参数键、新版本与显式车辆集合，整批原子切换。
 */
public record CanaryReleaseRequest(
        @NotNull String paramKey,
        @NotNull String version,
        @NotEmpty List<String> vehicles,
        String operator,
        String remark
) {
}
