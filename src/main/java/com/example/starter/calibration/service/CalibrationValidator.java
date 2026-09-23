package com.example.starter.calibration.service;

import com.example.starter.calibration.ValidationException;
import com.example.starter.calibration.api.CreateCalibrationRequest;
import com.example.starter.calibration.domain.ParamKind;
import com.example.starter.calibration.domain.SensorType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 标定内容与范围校验：
 * <ul>
 *   <li>数值必须有限（非 NaN/Infinity）；</li>
 *   <li>相机焦距 fx/fy 为正；</li>
 *   <li>外参旋转矩阵 3x3，且在配置容差内正交（R^T R ≈ I，det R ≈ +1）。</li>
 * </ul>
 * 校验失败抛 {@link ValidationException}（HTTP 422），失败版本保持 DRAFT 且不能被审批
 * （审批接口会重新执行同样的内容校验）。
 */
@Component
public class CalibrationValidator {

    private final double orthogonalityTolerance;

    public CalibrationValidator(
            @Value("${calibration.orthogonality-tolerance:1.0E-4}") double orthogonalityTolerance) {
        this.orthogonalityTolerance = orthogonalityTolerance;
    }

    /**
     * 校验请求字段、适用范围与参数内容。
     *
     * @throws ValidationException 任一规则不满足
     */
    public void validate(CreateCalibrationRequest request) {
        requireText(request.paramKey(), "paramKey");
        requireText(request.version(), "version");
        requireText(request.operator(), "operator");
        if (request.sensorType() == null) {
            throw new ValidationException("sensorType 不能为空");
        }
        if (request.kind() == null) {
            throw new ValidationException("kind 不能为空");
        }
        if (request.content() == null || request.content().isNull()) {
            throw new ValidationException("content 不能为空");
        }

        validateKindSensor(request.kind(), request.sensorType());
        validateRanges(request);
        validateContent(request.kind(), request.content());
    }

    private void validateKindSensor(ParamKind kind, SensorType sensorType) {
        switch (kind) {
            case INTRINSIC -> {
                if (sensorType != SensorType.CAMERA) {
                    throw new ValidationException("INTRINSIC 内参仅允许 CAMERA 传感器");
                }
            }
            case IMU -> {
                if (sensorType != SensorType.IMU) {
                    throw new ValidationException("IMU 参数仅允许 IMU 传感器");
                }
            }
            case EXTRINSIC -> {
                if (sensorType == SensorType.IMU) {
                    throw new ValidationException("EXTRINSIC 外参不允许 IMU 传感器");
                }
            }
        }
    }

    private void validateRanges(CreateCalibrationRequest request) {
        if (request.effectiveFrom() != null && request.effectiveTo() != null
                && !request.effectiveFrom().isBefore(request.effectiveTo())) {
            throw new ValidationException("生效时间范围非法：effectiveFrom 必须早于 effectiveTo");
        }
        checkStringRange(request.vehicleFrom(), request.vehicleTo(), "vehicle");
        checkStringRange(request.sensorFrom(), request.sensorTo(), "sensor");
        checkStringRange(request.mapFrom(), request.mapTo(), "map");
    }

    private void checkStringRange(String from, String to, String name) {
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new ValidationException(name + " 范围非法：下限不能大于上限");
        }
    }

    private void validateContent(ParamKind kind, JsonNode content) {
        checkFinite(content, "$");
        switch (kind) {
            case INTRINSIC -> validateIntrinsic(content);
            case EXTRINSIC -> validateExtrinsic(content);
            case IMU -> {
                // IMU 参数：全部数值有限即通过（零偏/比例因子/噪声等键名不做强制约束）
            }
        }
    }

    private void validateIntrinsic(JsonNode content) {
        double fx = requirePositiveFocal(content, "fx");
        double fy = requirePositiveFocal(content, "fy");
        if (Double.isNaN(fx) || Double.isNaN(fy)) {
            throw new ValidationException("相机焦距必须为有限正数");
        }
    }

    private double requirePositiveFocal(JsonNode content, String field) {
        JsonNode node = content.get(field);
        if (node == null || !node.isNumber()) {
            throw new ValidationException("相机内参缺少数值字段 " + field);
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value <= 0.0d) {
            throw new ValidationException("相机焦距 " + field + " 必须为正数");
        }
        return value;
    }

    private void validateExtrinsic(JsonNode content) {
        JsonNode rotation = content.has("rotation") ? content.get("rotation") : content.get("R");
        if (rotation == null || !rotation.isArray() || rotation.size() != 3) {
            throw new ValidationException("外参必须包含 3x3 旋转矩阵 rotation（或 R）");
        }
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            JsonNode row = rotation.get(i);
            if (row == null || !row.isArray() || row.size() != 3) {
                throw new ValidationException("外参旋转矩阵必须为 3x3");
            }
            for (int j = 0; j < 3; j++) {
                JsonNode cell = row.get(j);
                if (cell == null || !cell.isNumber()) {
                    throw new ValidationException("外参旋转矩阵元素必须全部为数值");
                }
                r[i][j] = cell.asDouble();
            }
        }
        checkOrthogonal(r);

        JsonNode translation = content.get("translation");
        if (translation != null && !translation.isNull()) {
            if (!translation.isArray() || translation.size() != 3) {
                throw new ValidationException("外参平移向量 translation 必须为长度 3 的数组");
            }
            for (int i = 0; i < 3; i++) {
                JsonNode cell = translation.get(i);
                if (cell == null || !cell.isNumber() || !Double.isFinite(cell.asDouble())) {
                    throw new ValidationException("外参平移向量元素必须为有限数值");
                }
            }
        }
    }

    /**
     * 检查旋转矩阵正交性：R^T R 与单位矩阵各元素偏差不超过容差，且行列式接近 +1。
     */
    private void checkOrthogonal(double[][] r) {
        double maxDeviation = 0.0d;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double dot = 0.0d;
                for (int k = 0; k < 3; k++) {
                    dot += r[k][i] * r[k][j];
                }
                double expected = i == j ? 1.0d : 0.0d;
                maxDeviation = Math.max(maxDeviation, Math.abs(dot - expected));
            }
        }
        if (maxDeviation > orthogonalityTolerance) {
            throw new ValidationException(
                    "外参旋转矩阵正交性超差：R^T*R 与单位矩阵最大偏差 " + maxDeviation
                            + " 超过容差 " + orthogonalityTolerance);
        }
        double det = determinant3(r);
        if (Math.abs(det - 1.0d) > orthogonalityTolerance) {
            throw new ValidationException(
                    "外参旋转矩阵行列式必须接近 +1，实际 " + det + "（容差 " + orthogonalityTolerance + "）");
        }
    }

    private double determinant3(double[][] r) {
        return r[0][0] * (r[1][1] * r[2][2] - r[1][2] * r[2][1])
                - r[0][1] * (r[1][0] * r[2][2] - r[1][2] * r[2][0])
                + r[0][2] * (r[1][0] * r[2][1] - r[1][1] * r[2][0]);
    }

    /** 递归检查 JSON 中所有数值节点均为有限值。 */
    void checkFinite(JsonNode node, String path) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            double value = node.asDouble();
            if (!Double.isFinite(value)) {
                throw new ValidationException("参数内容存在非有限数值（NaN/Infinity）：" + path);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> checkFinite(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                checkFinite(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " 不能为空");
        }
    }
}
