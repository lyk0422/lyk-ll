package com.example.starter.web.dto;

import com.example.starter.domain.Detection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * ID 切换请求：声明检测不再属于旧轨迹，而由新身份承接。
 */
public record IdSwitchRequest(
        @NotNull Long expectedVersion,
        @NotNull @Valid DetectionBody detection) {

    public Detection toDetection() {
        return detection.toDetection();
    }
}
