package com.example.starter.planningscene.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 位姿请求体，所有分量必须显式提供且为有限数。
 */
public record PoseDto(
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double z,
        @NotNull Double qx,
        @NotNull Double qy,
        @NotNull Double qz,
        @NotNull Double qw) {
}
