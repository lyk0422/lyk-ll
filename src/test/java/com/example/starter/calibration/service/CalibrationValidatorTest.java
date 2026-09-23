package com.example.starter.calibration.service;

import com.example.starter.calibration.ValidationException;
import com.example.starter.calibration.api.CreateCalibrationRequest;
import com.example.starter.calibration.domain.ParamKind;
import com.example.starter.calibration.domain.SensorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标定内容校验单元测试：有限数值、相机正焦距、外参旋转矩阵正交性。
 */
class CalibrationValidatorTest {

    private final CalibrationValidator validator = new CalibrationValidator(1.0E-4);
    private final ObjectMapper mapper = new ObjectMapper();

    private CreateCalibrationRequest request(SensorType type, ParamKind kind, JsonNode content) {
        return new CreateCalibrationRequest("k1", type, kind, "v1", content,
                null, null, null, null, null, null, null, null, "alice");
    }

    @Test
    void validCameraIntrinsicPasses() {
        JsonNode content = mapper.createObjectNode()
                .put("fx", 1000.0).put("fy", 980.0).put("cx", 640).put("cy", 360);
        assertThatCode(() -> validator.validate(request(SensorType.CAMERA, ParamKind.INTRINSIC, content)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonPositiveFocalLengthRejected() {
        JsonNode zero = mapper.createObjectNode().put("fx", 0.0).put("fy", 980.0);
        assertThatThrownBy(() -> validator.validate(request(SensorType.CAMERA, ParamKind.INTRINSIC, zero)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("fx");

        JsonNode negative = mapper.createObjectNode().put("fx", 1.0).put("fy", -2.0);
        assertThatThrownBy(() -> validator.validate(request(SensorType.CAMERA, ParamKind.INTRINSIC, negative)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("fy");
    }

    @Test
    void missingFocalFieldRejected() {
        JsonNode content = mapper.createObjectNode().put("cx", 640).put("cy", 360);
        assertThatThrownBy(() -> validator.validate(request(SensorType.CAMERA, ParamKind.INTRINSIC, content)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("fx");
    }

    @Test
    void nonFiniteValuesRejected() {
        com.fasterxml.jackson.databind.node.ObjectNode nan = mapper.createObjectNode();
        nan.set("fx", JsonNodeFactory.instance.numberNode(Double.NaN));
        nan.put("fy", 1.0);
        assertThatThrownBy(() -> validator.validate(request(SensorType.CAMERA, ParamKind.INTRINSIC, nan)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("非有限数值");

        com.fasterxml.jackson.databind.node.ObjectNode infinity = mapper.createObjectNode();
        infinity.set("fx", JsonNodeFactory.instance.numberNode(Double.POSITIVE_INFINITY));
        infinity.put("fy", 1.0);
        assertThatThrownBy(() -> validator
                .validate(request(SensorType.CAMERA, ParamKind.INTRINSIC, infinity)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("非有限数值");
    }

    @Test
    void validExtrinsicIdentityRotationPasses() throws Exception {
        JsonNode content = mapper.readTree(createExtrinsic(
                new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
                new double[]{0.1, -0.2, 0.3}));
        assertThatCode(() -> validator.validate(request(SensorType.LIDAR, ParamKind.EXTRINSIC, content)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonOrthogonalRotationRejected() throws Exception {
        JsonNode content = mapper.readTree(createExtrinsic(
                new double[][]{{1.1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
                new double[]{0, 0, 0}));
        assertThatThrownBy(() -> validator.validate(request(SensorType.LIDAR, ParamKind.EXTRINSIC, content)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("正交");
    }

    @Test
    void reflectionRotationDeterminantMinusOneRejected() throws Exception {
        JsonNode content = mapper.readTree(createExtrinsic(
                new double[][]{{-1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
                new double[]{0, 0, 0}));
        assertThatThrownBy(() -> validator.validate(request(SensorType.RADAR, ParamKind.EXTRINSIC, content)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("行列式");
    }

    @Test
    void toleranceConfigurableAndNearOrthogonalAccepted() throws Exception {
        CalibrationValidator loose = new CalibrationValidator(1.0E-2);
        JsonNode content = mapper.readTree(createExtrinsic(
                new double[][]{{1.003, 0, 0}, {0, 1, 0}, {0, 0, 1}},
                new double[]{0, 0, 0}));
        // 1.003^2-1 ≈ 0.006 < 0.01 容差，行列式 1.003 偏差 0.003 < 0.01
        assertThatCode(() -> loose.validate(request(SensorType.CAMERA, ParamKind.EXTRINSIC, content)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request(SensorType.CAMERA, ParamKind.EXTRINSIC, content)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void kindAndSensorTypeMustBeConsistent() {
        JsonNode intrinsic = mapper.createObjectNode().put("fx", 1.0).put("fy", 1.0);
        assertThatThrownBy(() -> validator.validate(request(SensorType.LIDAR, ParamKind.INTRINSIC, intrinsic)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("CAMERA");

        JsonNode imu = mapper.createObjectNode().put("gyroBias", 0.01);
        assertThatThrownBy(() -> validator.validate(request(SensorType.CAMERA, ParamKind.IMU, imu)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("IMU");
    }

    @Test
    void invertedTimeRangeRejected() {
        JsonNode intrinsic = mapper.createObjectNode().put("fx", 1.0).put("fy", 1.0);
        CreateCalibrationRequest bad = new CreateCalibrationRequest("k1", SensorType.CAMERA,
                ParamKind.INTRINSIC, "v1", intrinsic,
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null, "alice");
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("生效时间");
    }

    private String createExtrinsic(double[][] r, double[] t) {
        StringBuilder sb = new StringBuilder("{\"rotation\":[");
        for (int i = 0; i < 3; i++) {
            sb.append('[');
            for (int j = 0; j < 3; j++) {
                sb.append(r[i][j]).append(j == 2 ? "" : ",");
            }
            sb.append(i == 2 ? "]" : "],");
        }
        sb.append("],\"translation\":[").append(t[0]).append(',').append(t[1]).append(',').append(t[2])
                .append("]}");
        return sb.toString();
    }
}
