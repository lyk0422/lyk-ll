package com.example.starter.tf.api;

import jakarta.validation.constraints.NotNull;

/**
 * 刚体位姿：平移单位米，旋转为单位四元数（Hamilton 约定）。
 */
public record PoseDto(@NotNull Double tx,
                      @NotNull Double ty,
                      @NotNull Double tz,
                      @NotNull Double qx,
                      @NotNull Double qy,
                      @NotNull Double qz,
                      @NotNull Double qw) {
}
