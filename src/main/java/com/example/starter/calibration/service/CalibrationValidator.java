package com.example.starter.calibration.service;

import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.SensorType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标定参数内容校验：
 * <ul>
 *     <li>所有数值必须有限（非 NaN/Infinity）；</li>
 *     <li>相机内参焦距 fx/fy 必须为正；</li>
 *     <li>外参旋转矩阵 3x3 且在配置容差内正交（R^T*R≈I 且 det(R)≈1）；</li>
 *     <li>参数类别与传感器类型必须匹配。</li>
 * </ul>
 * 校验失败抛 {@link ValidationException}（HTTP 422），不允许进入审批流程。
 */
@Component
public class CalibrationValidator {

    private final double orthogonalityTolerance;

    public CalibrationValidator(
            @Value("${calibration.orthogonality-tolerance:0.01}") double orthogonalityTolerance) {
        if (orthogonalityTolerance <= 0) {
            throw new IllegalArgumentException("orthogonalityTolerance 必须为正数");
        }
        this.orthogonalityTolerance = orthogonalityTolerance;
    }

    /**
     * 校验参数内容，失败抛 {@link ValidationException}。
     */
    public void validate(CalibrationKind kind, SensorType sensorType, JsonNode content) {
        if (content == null || content.isNull() || !content.isObject()) {
            throw new ValidationException("参数内容必须为 JSON 对象");
        }
        validateKindSensor(kind, sensorType);
        switch (kind) {
            case INTRINSIC -> validateIntrinsic(content);
            case EXTRINSIC -> validateExtrinsic(content);
            case IMU_PARAMS -> validateImuParams(content);
        }
    }

    private void validateKindSensor(CalibrationKind kind, SensorType sensorType) {
        switch (kind) {
            case INTRINSIC -> {
                if (sensorType != SensorType.CAMERA) {
                    throw new ValidationException("内参仅适用于 CAMERA 传感器，实际为 " + sensorType);
                }
            }
            case EXTRINSIC -> {
                if (sensorType == SensorType.IMU) {
                    throw new ValidationException("外参适用于 CAMERA/LIDAR/RADAR，不接受 IMU 类型");
                }
            }
            case IMU_PARAMS -> {
                if (sensorType != SensorType.IMU) {
                    throw new ValidationException("IMU 参数仅适用于 IMU 传感器，实际为 " + sensorType);
                }
            }
        }
    }

    /**
     * 相机内参结构：{"fx":正,"fy":正,"cx":有限,"cy":有限,"distortion":[有限...]}，
     * 其余附加数值字段同样必须有限。
     */
    private void validateIntrinsic(JsonNode content) {
        ensureFiniteNumber(content, "fx");
        ensureFiniteNumber(content, "fy");
        ensureFiniteNumber(content, "cx");
        ensureFiniteNumber(content, "cy");
        double fx = content.get("fx").asDouble();
        double fy = content.get("fy").asDouble();
        if (fx <= 0 || fy <= 0) {
            throw new ValidationException(
                    "相机焦距必须为正数，实际 fx=" + fx + ", fy=" + fy);
        }
        if (content.has("distortion")) {
            JsonNode distortion = content.get("distortion");
            if (!distortion.isArray()) {
                throw new ValidationException("distortion 必须为数值数组");
            }
        }
        assertAllNumbersFinite(content, "content");
    }

    /**
     * 外参结构：{"rotation":3x3 矩阵,"translation":[x,y,z]}。
     */
    private void validateExtrinsic(JsonNode content) {
        JsonNode rotation = content.get("rotation");
        if (rotation == null || !rotation.isArray() || rotation.size() != 3) {
            throw new ValidationException("外参 rotation 必须为 3x3 旋转矩阵");
        }
        double[][] r = new double[3][3];
        for (int row = 0; row < 3; row++) {
            JsonNode rowNode = rotation.get(row);
            if (rowNode == null || !rowNode.isArray() || rowNode.size() != 3) {
                throw new ValidationException("外参 rotation 必须为 3x3 旋转矩阵");
            }
            for (int col = 0; col < 3; col++) {
                JsonNode v = rowNode.get(col);
                if (v == null || !v.isNumber() || !isFinite(v.asDouble())) {
                    throw new ValidationException(
                            "外参 rotation[" + row + "][" + col + "] 必须为有限数值");
                }
                r[row][col] = v.asDouble();
            }
        }
        validateOrthogonal(r);

        JsonNode translation = content.get("translation");
        if (translation == null || !translation.isArray() || translation.size() != 3) {
            throw new ValidationException("外参 translation 必须为长度 3 的数值数组");
        }
        for (int i = 0; i < 3; i++) {
            JsonNode v = translation.get(i);
            if (v == null || !v.isNumber() || !isFinite(v.asDouble())) {
                throw new ValidationException("外参 translation[" + i + "] 必须为有限数值");
            }
        }
        // 旋转与平移之外的附加字段也不允许出现非有限数值
        assertAllNumbersFinite(content, "content");
    }

    /**
     * 检查 R^T*R 与单位矩阵各元素偏差不超过容差，且行列式接近 1（纯旋转而非反射）。
     */
    private void validateOrthogonal(double[][] r) {
        double[][] product = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += r[k][i] * r[k][j];
                }
                product[i][j] = sum;
            }
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double expected = i == j ? 1.0 : 0.0;
                double diff = Math.abs(product[i][j] - expected);
                if (diff > orthogonalityTolerance) {
                    violations.add("(R^T*R)[" + i + "][" + j + "]="
                            + round(product[i][j]) + " 期望 " + expected + " 偏差 " + round(diff));
                }
            }
        }
        double det = determinant3(r);
        if (Math.abs(det - 1.0) > orthogonalityTolerance) {
            violations.add("det(R)=" + round(det) + " 期望 1.0（纯旋转矩阵）");
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(
                    "外参旋转矩阵在容差 " + orthogonalityTolerance + " 内不满足正交性: "
                            + String.join("; ", violations));
        }
    }

    /**
     * IMU 参数：结构不做强约束（陀螺仪/加表零偏、比例因子、噪声、安装角等），
     * 但所有数值必须有限，且至少包含一个字段。
     */
    private void validateImuParams(JsonNode content) {
        if (content.isEmpty()) {
            throw new ValidationException("IMU 参数内容不能为空");
        }
        assertAllNumbersFinite(content, "content");
    }

    private void ensureFiniteNumber(JsonNode content, String field) {
        JsonNode v = content.get(field);
        if (v == null || !v.isNumber()) {
            throw new ValidationException("缺少数值字段 " + field);
        }
        if (!isFinite(v.asDouble())) {
            throw new ValidationException("字段 " + field + " 必须为有限数值，实际 " + v.asDouble());
        }
    }

    /**
     * 递归校验 JSON 中全部数值节点有限（排除已显式校验的 rotation 也无妨，结论一致）。
     */
    private void assertAllNumbersFinite(JsonNode node, String path) {
        if (node.isNumber()) {
            double d = node.asDouble();
            if (!isFinite(d)) {
                throw new ValidationException("字段 " + path + " 必须为有限数值，实际 " + d);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertAllNumbersFinite(node.get(i), path + "[" + i + "]");
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    assertAllNumbersFinite(e.getValue(), path + "." + e.getKey()));
        }
    }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static double determinant3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static String round(double v) {
        return String.format("%.6f", v);
    }
}
