package com.example.starter.calibration.domain.payload;

import java.util.List;

/**
 * 相机内参。
 *
 * @param fx                 x 方向焦距，单位像素，必须为正
 * @param fy                 y 方向焦距，单位像素，必须为正
 * @param cx                 主点 x 坐标，单位像素
 * @param cy                 主点 y 坐标，单位像素
 * @param distortionCoeffs   畸变系数（可为空表示不考虑畸变），单位 1
 */
public record CameraIntrinsics(
        double fx,
        double fy,
        double cx,
        double cy,
        List<Double> distortionCoeffs) {
}
