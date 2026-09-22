package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 路线请求提交入参；requestId 为幂等键，请求固定绑定一个明确地图版本。
 *
 * @param requestId    请求业务标识
 * @param mapVersionId 明确绑定的地图版本标识
 * @param origin       起点匹配输入
 * @param destination  终点匹配输入
 */
public record SubmitRouteRequest(
        @NotBlank String requestId,
        @NotBlank String mapVersionId,
        @NotNull @Valid PointDto origin,
        @NotNull @Valid PointDto destination) {
}
