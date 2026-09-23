package com.example.starter.calibration.domain;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.starter.calibration.domain.payload.CalibrationPayload;
import com.example.starter.calibration.domain.payload.CameraIntrinsics;
import com.example.starter.calibration.domain.payload.Extrinsics;
import com.example.starter.calibration.domain.payload.ImuParameters;
import com.example.starter.calibration.web.ApiException;

/**
 * 标定参数内容校验：数值必须有限；相机焦距为正；外参旋转矩阵在配置容差内满足正交性。
 * 校验失败抛出 422，调用方不得据此创建可审批版本。
 */
@Component
public class CalibrationContentValidator {

    private final double rotationTolerance;

    public CalibrationContentValidator(
            @Value("${calibration.validation.rotation-tolerance:1.0E-4}") double rotationTolerance) {
        if (!(rotationTolerance > 0) || Double.isNaN(rotationTolerance)) {
            throw new IllegalStateException("rotation-tolerance 必须为正数");
        }
        this.rotationTolerance = rotationTolerance;
    }

    /**
     * 按传感器类型校验载荷，失败时抛出 {@link ApiException.Unprocessable}。
     */
    public void validate(SensorType sensorType, CalibrationPayload payload) {
        if (payload == null) {
            throw new ApiException.Unprocessable("标定内容 payload 不能为空");
        }
        switch (sensorType) {
            case CAMERA -> validateCamera(payload);
            case LIDAR, RADAR -> validateExtrinsicSensor(sensorType, payload);
            case IMU -> validateImu(payload);
        }
    }

    private void validateCamera(CalibrationPayload payload) {
        CameraIntrinsics intrinsics = payload.cameraIntrinsics();
        if (intrinsics == null) {
            throw new ApiException.Unprocessable("相机标定必须包含 cameraIntrinsics 内参");
        }
        if (!Double.isFinite(intrinsics.fx()) || intrinsics.fx() <= 0.0) {
            throw new ApiException.Unprocessable("相机焦距 fx 必须为正有限值，实际: " + intrinsics.fx());
        }
        if (!Double.isFinite(intrinsics.fy()) || intrinsics.fy() <= 0.0) {
            throw new ApiException.Unprocessable("相机焦距 fy 必须为正有限值，实际: " + intrinsics.fy());
        }
        if (!Double.isFinite(intrinsics.cx()) || !Double.isFinite(intrinsics.cy())) {
            throw new ApiException.Unprocessable("相机主点 cx/cy 必须为有限值");
        }
        if (intrinsics.distortionCoeffs() != null) {
            for (Double coefficient : intrinsics.distortionCoeffs()) {
                if (coefficient == null || !Double.isFinite(coefficient)) {
                    throw new ApiException.Unprocessable("畸变系数必须全部有限且非空");
                }
            }
        }
        // 相机外参若提供则同样必须满足正交性
        if (payload.extrinsics() != null) {
            validateExtrinsics(payload.extrinsics());
        }
    }

    private void validateExtrinsicSensor(SensorType sensorType, CalibrationPayload payload) {
        if (payload.extrinsics() == null) {
            throw new ApiException.Unprocessable(sensorType + " 标定必须包含 extrinsics 外参");
        }
        validateExtrinsics(payload.extrinsics());
    }

    private void validateImu(CalibrationPayload payload) {
        ImuParameters imu = payload.imu();
        if (imu == null) {
            throw new ApiException.Unprocessable("IMU 标定必须包含 imu 参数");
        }
        requireFiniteVector(imu.accelerometerBias(), 3, "加速度计零偏");
        requireFiniteVector(imu.gyroscopeBias(), 3, "陀螺仪零偏");
        if (!Double.isFinite(imu.accelerometerNoiseDensity()) || imu.accelerometerNoiseDensity() < 0.0) {
            throw new ApiException.Unprocessable("加速度计噪声密度必须为非负有限值");
        }
        if (!Double.isFinite(imu.gyroscopeNoiseDensity()) || imu.gyroscopeNoiseDensity() < 0.0) {
            throw new ApiException.Unprocessable("陀螺仪噪声密度必须为非负有限值");
        }
    }

    private void requireFiniteVector(List<Double> values, int expectedSize, String name) {
        if (values == null || values.size() != expectedSize) {
            throw new ApiException.Unprocessable(name + " 必须是长度为 " + expectedSize + " 的向量");
        }
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new ApiException.Unprocessable(name + " 的分量必须全部有限且非空");
            }
        }
    }

    /**
     * 校验外参：平移为长度 3 的有限向量；旋转为 3x3 有限矩阵，
     * 且 |R^T R - I| 各元素及 |det(R)-1| 不超过配置容差。
     */
    public void validateExtrinsics(Extrinsics extrinsics) {
        requireFiniteVector(extrinsics.translation(), 3, "外参平移向量");
        List<List<Double>> rotation = extrinsics.rotation();
        if (rotation == null || rotation.size() != 3) {
            throw new ApiException.Unprocessable("外参旋转矩阵必须是 3x3 矩阵");
        }
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            List<Double> row = rotation.get(i);
            if (row == null || row.size() != 3) {
                throw new ApiException.Unprocessable("外参旋转矩阵必须是 3x3 矩阵");
            }
            for (int j = 0; j < 3; j++) {
                Double value = row.get(j);
                if (value == null || !Double.isFinite(value)) {
                    throw new ApiException.Unprocessable("外参旋转矩阵元素必须全部有限且非空");
                }
                r[i][j] = value;
            }
        }
        // R^T R 应等于单位矩阵
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double dot = 0.0;
                for (int k = 0; k < 3; k++) {
                    dot += r[k][i] * r[k][j];
                }
                double expected = i == j ? 1.0 : 0.0;
                if (Math.abs(dot - expected) > rotationTolerance) {
                    throw new ApiException.Unprocessable(
                            "旋转矩阵不满足正交性（容差 " + rotationTolerance + "）：R^T R[" + i + "][" + j
                                    + "]=" + dot + "，期望 " + expected);
                }
            }
        }
        double determinant = determinant3(r);
        if (Math.abs(determinant - 1.0) > rotationTolerance) {
            throw new ApiException.Unprocessable(
                    "旋转矩阵行列式必须近似 +1（容差 " + rotationTolerance + "），实际: " + determinant);
        }
    }

    private static double determinant3(double[][] r) {
        return r[0][0] * (r[1][1] * r[2][2] - r[1][2] * r[2][1])
                - r[0][1] * (r[1][0] * r[2][2] - r[1][2] * r[2][0])
                + r[0][2] * (r[1][0] * r[2][1] - r[1][1] * r[2][0]);
    }
}
