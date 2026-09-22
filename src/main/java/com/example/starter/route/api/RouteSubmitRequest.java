package com.example.starter.route.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 路线匹配请求。requestId 为幂等键，mapVersion 在首次提交时被固定绑定。
 */
public record RouteSubmitRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String mapVersion,
        @NotNull @Valid PointInput start,
        @NotNull @Valid PointInput end) {

    /**
     * 带航向的定位输入。
     *
     * @param x       平面 X 坐标，单位米
     * @param y       平面 Y 坐标，单位米
     * @param heading 航向角，单位度（正北顺时针，自动归一化到 0~360）
     */
    public record PointInput(
            @NotNull Double x,
            @NotNull Double y,
            @NotNull Double heading) {
    }
}
