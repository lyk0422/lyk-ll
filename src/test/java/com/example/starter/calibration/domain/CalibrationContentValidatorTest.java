package com.example.starter.calibration.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.domain.payload.CalibrationPayload;
import com.example.starter.calibration.domain.payload.CameraIntrinsics;
import com.example.starter.calibration.domain.payload.Extrinsics;
import com.example.starter.calibration.domain.payload.ImuParameters;
import com.example.starter.calibration.web.ApiException;

/**
 * 标定内容数值校验单元测试：有限性、相机焦距为正、旋转矩阵容差内正交。
 */
class CalibrationContentValidatorTest {

    private final CalibrationContentValidator validator =
            new CalibrationContentValidator(1.0E-6);

    private static Extrinsics identityExtrinsics() {
        return new Extrinsics(
                List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0), List.of(0.0, 0.0, 1.0)),
                List.of(0.1, 0.2, 0.3));
    }

    private static CameraIntrinsics intrinsics(double fx, double fy) {
        return new CameraIntrinsics(fx, fy, 640.0, 360.0, List.of(0.0, 0.0, 0.0));
    }

    @Test
    void validCameraCalibrationPasses() {
        CalibrationPayload payload =
                new CalibrationPayload(intrinsics(1000.0, 1000.5), identityExtrinsics(), null);
        assertThatCode(() -> validator.validate(SensorType.CAMERA, payload)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveFocalLength() {
        CalibrationPayload payload = new CalibrationPayload(intrinsics(0.0, 1000.0), null, null);
        assertThatThrownBy(() -> validator.validate(SensorType.CAMERA, payload))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("焦距 fx");
    }

    @Test
    void rejectsInfiniteFocalLength() {
        CalibrationPayload payload =
                new CalibrationPayload(intrinsics(Double.POSITIVE_INFINITY, 1.0), null, null);
        assertThatThrownBy(() -> validator.validate(SensorType.CAMERA, payload))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("焦距 fx");
    }

    @Test
    void rejectsNaNDistortionCoefficient() {
        CameraIntrinsics bad = new CameraIntrinsics(100.0, 100.0, 0.0, 0.0, List.of(Double.NaN));
        assertThatThrownBy(() -> validator.validate(SensorType.CAMERA,
                new CalibrationPayload(bad, null, null)))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("畸变系数");
    }

    @Test
    void rejectsMissingCameraIntrinsics() {
        assertThatThrownBy(() -> validator.validate(SensorType.CAMERA,
                new CalibrationPayload(null, identityExtrinsics(), null)))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("cameraIntrinsics");
    }

    @Test
    void rejectsNonOrthogonalRotation() {
        Extrinsics bad = new Extrinsics(
                List.of(List.of(1.0, 0.1, 0.0), List.of(0.0, 1.0, 0.0), List.of(0.0, 0.0, 1.0)),
                List.of(0.0, 0.0, 0.0));
        assertThatThrownBy(() -> validator.validate(SensorType.LIDAR,
                new CalibrationPayload(null, bad, null)))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("正交性");
    }

    @Test
    void rejectsReflectionMatrix() {
        Extrinsics reflection = new Extrinsics(
                List.of(List.of(-1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0), List.of(0.0, 0.0, 1.0)),
                List.of(0.0, 0.0, 0.0));
        assertThatThrownBy(() -> validator.validateExtrinsics(reflection))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("行列式");
    }

    @Test
    void rejectsNonFiniteTranslation() {
        Extrinsics bad = new Extrinsics(
                List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0), List.of(0.0, 0.0, 1.0)),
                List.of(0.0, Double.NaN, 0.0));
        assertThatThrownBy(() -> validator.validate(SensorType.RADAR,
                new CalibrationPayload(null, bad, null)))
                .isInstanceOf(ApiException.Unprocessable.class);
    }

    @Test
    void rejectsMalformedRotationShape() {
        Extrinsics bad = new Extrinsics(
                List.of(List.of(1.0, 0.0), List.of(0.0, 1.0)),
                List.of(0.0, 0.0, 0.0));
        assertThatThrownBy(() -> validator.validate(SensorType.LIDAR,
                new CalibrationPayload(null, bad, null)))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("3x3");
    }

    @Test
    void nearOrthogonalMatrixWithinTolerancePasses() {
        // 轻微扰动但仍在 1E-4 容差内
        Extrinsics near = new Extrinsics(
                List.of(
                        List.of(0.99999999, 0.00000001, 0.0),
                        List.of(-0.00000001, 0.99999999, 0.0),
                        List.of(0.0, 0.0, 1.0)),
                List.of(0.0, 0.0, 0.0));
        assertThatCode(() -> validator.validate(SensorType.LIDAR,
                new CalibrationPayload(null, near, null))).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidImuParameters() {
        ImuParameters bad = new ImuParameters(List.of(0.0, 0.0), List.of(0.0, 0.0, 0.0), 1.0, 1.0);
        assertThatThrownBy(() -> validator.validate(SensorType.IMU,
                new CalibrationPayload(null, null, bad)))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("加速度计零偏");

        ImuParameters negativeNoise = new ImuParameters(
                List.of(0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0), -1.0, 0.0);
        assertThatThrownBy(() -> validator.validate(SensorType.IMU,
                new CalibrationPayload(null, null, negativeNoise)))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("噪声密度");
    }

    @Test
    void validImuParametersPass() {
        ImuParameters imu = new ImuParameters(
                List.of(0.01, -0.02, 0.98), List.of(0.001, 0.002, 0.003), 0.0005, 0.00002);
        assertThatCode(() -> validator.validate(SensorType.IMU,
                new CalibrationPayload(null, null, imu))).doesNotThrowAnyException();
    }
}
