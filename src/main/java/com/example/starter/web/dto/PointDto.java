package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * 匹配输入点。
 *
 * @param x       坐标 X，单位米
 * @param y       坐标 Y，单位米
 * @param heading 航向角，单位度，范围 [0,360)
 */
public record PointDto(
        double x,
        double y,
        @DecimalMin("0") @DecimalMax(value = "360", inclusive = false) double heading) {
}
