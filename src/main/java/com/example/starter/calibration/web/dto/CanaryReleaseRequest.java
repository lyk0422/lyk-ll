package com.example.starter.calibration.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 灰度发布请求：显式指定参与灰度的车辆集合。
 *
 * @param key      标定参数唯一键
 * @param version  要灰度的已批准版本号
 * @param vehicles 参与灰度的车辆集合，非空
 * @param operator 发布操作者
 */
public record CanaryReleaseRequest(
        @NotBlank String key,
        @NotBlank String version,
        @NotEmpty List<@NotBlank String> vehicles,
        @NotBlank String operator) {
}
