package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.SensorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 创建标定参数版本请求。
 *
 * @param effectiveFrom 生效起始时间（UTC，含），null 不限制
 * @param effectiveTo   生效结束时间（UTC，不含），null 不限制
 * @param vehicleScope  适用车辆集合，null/空表示全部车辆
 * @param sensorScope   适用传感器集合，null/空表示不额外限制
 * @param mapVersionMin 地图版本下界（含），null 不限制
 * @param mapVersionMax 地图版本上界（含），null 不限制
 */
public record CreateParamRequest(
        @NotBlank String paramKey,
        @NotNull CalibrationKind kind,
        @NotNull SensorType sensorType,
        @NotBlank String version,
        @NotNull Object content,
        Instant effectiveFrom,
        Instant effectiveTo,
        List<String> vehicleScope,
        List<String> sensorScope,
        String mapVersionMin,
        String mapVersionMax,
        String operator
) {
}
