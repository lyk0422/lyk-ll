package com.example.starter.slam.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 写入图边请求（里程计边或回环约束）。
 *
 * @param type       ODOMETRY / LOOP
 * @param version    来源系统内的版本号，从 0 起单调递增
 * @param covariance 6x6 位姿协方差（36 个元素，行优先）
 * @param score      回环匹配分数，仅 LOOP 必填
 * @param residual   回环初始残差，仅 LOOP 必填
 */
public record EdgeRequest(
        @NotBlank String id,
        @NotBlank String type,
        @NotBlank String source,
        @Min(0) int version,
        @NotBlank String fromKeyframeId,
        @NotBlank String toKeyframeId,
        double dx,
        double dy,
        double dtheta,
        @NotNull @Size(min = 36, max = 36) List<@Valid Double> covariance,
        Double score,
        Double residual,
        @NotBlank String requestId
) {
}
