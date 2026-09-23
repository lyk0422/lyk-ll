package com.example.starter.calibration.domain.payload;

import java.util.List;

/**
 * IMU 参数。
 *
 * @param accelerometerBias        加速度计零偏 [x,y,z]，单位 m/s^2
 * @param gyroscopeBias            陀螺仪零偏 [x,y,z]，单位 rad/s
 * @param accelerometerNoiseDensity 加速度计噪声密度，单位 ug/sqrt(Hz) 对应数值，必须非负
 * @param gyroscopeNoiseDensity     陀螺仪噪声密度，单位 rad/s/sqrt(Hz)，必须非负
 */
public record ImuParameters(
        List<Double> accelerometerBias,
        List<Double> gyroscopeBias,
        double accelerometerNoiseDensity,
        double gyroscopeNoiseDensity) {
}
