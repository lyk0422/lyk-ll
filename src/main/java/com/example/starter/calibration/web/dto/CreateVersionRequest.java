package com.example.starter.calibration.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.domain.payload.CalibrationPayload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建标定版本请求。
 *
 * @param key            标定参数唯一键
 * @param sensorType     传感器类型
 * @param version        版本号（同一标定键下唯一）
 * @param payload        标定内容
 * @param effectiveFrom  生效起始时间（含）
 * @param effectiveTo    生效结束时间（不含），为空表示长期有效
 * @param allVehicles    是否对全部车辆生效；false 时使用 vehicles
 * @param vehicles       allVehicles=false 时的显式车辆集合
 * @param sensorId       传感器标识
 * @param mapVersionFrom 地图版本下界（含），可空
 * @param mapVersionTo   地图版本上界（含），可空
 * @param operator       创建操作者
 */
public record CreateVersionRequest(
        @NotBlank String key,
        @NotNull SensorType sensorType,
        @NotBlank String version,
        @NotNull CalibrationPayload payload,
        @NotNull OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        Boolean allVehicles,
        List<String> vehicles,
        @NotBlank String sensorId,
        Integer mapVersionFrom,
        Integer mapVersionTo,
        @NotBlank String operator) {
}
