package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.ParamKind;
import com.example.starter.calibration.domain.SensorType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 创建标定参数版本请求。范围端点 null 表示开放端；时间为 UTC。
 *
 * @param operator 创建操作者
 */
public record CreateCalibrationRequest(
        String paramKey,
        SensorType sensorType,
        ParamKind kind,
        String version,
        JsonNode content,
        Instant effectiveFrom,
        Instant effectiveTo,
        String vehicleFrom,
        String vehicleTo,
        String sensorFrom,
        String sensorTo,
        String mapFrom,
        String mapTo,
        String operator) {
}
