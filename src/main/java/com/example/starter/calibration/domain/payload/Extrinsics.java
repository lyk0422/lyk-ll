package com.example.starter.calibration.domain.payload;

import java.util.List;

/**
 * 相机/LiDAR/Radar 相对车体坐标系的外参。
 *
 * @param rotation    3x3 旋转矩阵（无量纲），在配置容差内正交且行列式近似 +1
 * @param translation 平移向量 [x,y,z]，单位米
 */
public record Extrinsics(
        List<List<Double>> rotation,
        List<Double> translation) {
}
