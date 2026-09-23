package com.example.starter.calibration.service;

import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.SensorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标定内容校验单元测试：相机焦距为正、数值有限、旋转矩阵容差内正交、类别匹配。
 */
class CalibrationValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CalibrationValidator validator = new CalibrationValidator(0.01);

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String goodIntrinsic() {
        return "{\"fx\":1050.0,\"fy\":1048.5,\"cx\":960,\"cy\":540,"
                + "\"distortion\":[-0.05,0.01,0,0,0]}";
    }

    @Test
    void acceptsValidCameraIntrinsic() {
        assertDoesNotThrow(() -> validator.validate(
                CalibrationKind.INTRINSIC, SensorType.CAMERA, json(goodIntrinsic())));
    }

    @Test
    void rejectsNegativeFocalLength() {
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.INTRINSIC, SensorType.CAMERA,
                json("{\"fx\":-1,\"fy\":1048.5,\"cx\":960,\"cy\":540}")));
        assertTrue(ex.getMessage().contains("焦距"), ex.getMessage());
    }

    @Test
    void rejectsZeroFocalLength() {
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.INTRINSIC, SensorType.CAMERA,
                json("{\"fx\":0,\"fy\":0,\"cx\":960,\"cy\":540}")));
    }

    @Test
    void rejectsNonFiniteNumber() {
        // 1e999 解析为 Infinity
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.INTRINSIC, SensorType.CAMERA,
                json("{\"fx\":1e999,\"fy\":1048.5,\"cx\":960,\"cy\":540}")));
        assertTrue(ex.getMessage().contains("有限"), ex.getMessage());
    }

    @Test
    void rejectsNonFiniteNestedNumber() {
        // 1e400 超出 double 范围，Jackson 解析为 Infinity
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.INTRINSIC, SensorType.CAMERA,
                json("{\"fx\":1050,\"fy\":1048,\"cx\":960,\"cy\":540,"
                        + "\"distortion\":[1e400,0,0,0,0]}")));
    }

    @Test
    void rejectsIntrinsicForNonCameraSensor() {
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.INTRINSIC, SensorType.LIDAR, json(goodIntrinsic())));
        assertTrue(ex.getMessage().contains("CAMERA"), ex.getMessage());
    }

    @Test
    void acceptsIdentityRotationExtrinsic() {
        String body = "{\"rotation\":[[1,0,0],[0,1,0],[0,0,1]],"
                + "\"translation\":[1.5,-0.2,0.0]}";
        assertDoesNotThrow(() -> validator.validate(
                CalibrationKind.EXTRINSIC, SensorType.LIDAR, json(body)));
        assertDoesNotThrow(() -> validator.validate(
                CalibrationKind.EXTRINSIC, SensorType.RADAR, json(body)));
    }

    @Test
    void rejectsNonOrthogonalRotation() {
        // 行向量非单位且不正交
        String body = "{\"rotation\":[[1,0.2,0],[0,1,0],[0,0,1]],"
                + "\"translation\":[1,2,3]}";
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.EXTRINSIC, SensorType.CAMERA, json(body)));
        assertTrue(ex.getMessage().contains("正交"), ex.getMessage());
    }

    @Test
    void rejectsReflectionMatrixWithOrthogonalColumnsButDetMinusOne() {
        // 正交但行列式 -1（反射），不是合法旋转
        String body = "{\"rotation\":[[1,0,0],[0,1,0],[0,0,-1]],"
                + "\"translation\":[1,2,3]}";
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.EXTRINSIC, SensorType.LIDAR, json(body)));
        assertTrue(ex.getMessage().contains("det(R)"), ex.getMessage());
    }

    @Test
    void rejectsMalformedRotationShape() {
        String body = "{\"rotation\":[[1,0],[0,1]],\"translation\":[1,2,3]}";
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.EXTRINSIC, SensorType.LIDAR, json(body)));
    }

    @Test
    void rejectsExtrinsicForImu() {
        String body = "{\"rotation\":[[1,0,0],[0,1,0],[0,0,1]],\"translation\":[1,2,3]}";
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.EXTRINSIC, SensorType.IMU, json(body)));
    }

    @Test
    void acceptsFiniteImuParamsAndRejectsEmpty() {
        String imu = "{\"gyro_bias\":[0.01,-0.02,0.0],\"accel_bias\":[0,0,0.03],"
                + "\"scale_factor\":1.002,\"noise_density\":0.0005}";
        assertDoesNotThrow(() -> validator.validate(
                CalibrationKind.IMU_PARAMS, SensorType.IMU, json(imu)));
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.IMU_PARAMS, SensorType.IMU, json("{}")));
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.IMU_PARAMS, SensorType.CAMERA, json(imu)));
    }

    @Test
    void rejectsNonObjectContent() {
        assertThrows(ValidationException.class, () -> validator.validate(
                CalibrationKind.IMU_PARAMS, SensorType.IMU, json("[1,2,3]")));
    }
}
