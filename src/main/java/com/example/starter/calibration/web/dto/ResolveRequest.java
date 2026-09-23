package com.example.starter.calibration.web.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;

/**
 * 参数解析请求。
 *
 * @param key        标定参数唯一键
 * @param sensorId   传感器标识
 * @param vehicleId  车辆标识
 * @param mapVersion 地图版本，可空表示不区分地图版本
 * @param time       解析业务时间，可空表示使用服务当前时间
 */
public record ResolveRequest(
        @NotBlank String key,
        @NotBlank String sensorId,
        @NotBlank String vehicleId,
        Integer mapVersion,
        OffsetDateTime time) {
}
