package com.example.starter.calibration.domain.payload;

/**
 * 一份标定参数的完整内容，按传感器类型使用对应字段。
 *
 * @param cameraIntrinsics 相机内参，仅 CAMERA 必填
 * @param extrinsics       外参，CAMERA/LIDAR/RADAR 校验；IMU 可为空
 * @param imu              IMU 参数，仅 IMU 必填
 */
public record CalibrationPayload(
        CameraIntrinsics cameraIntrinsics,
        Extrinsics extrinsics,
        ImuParameters imu) {
}
