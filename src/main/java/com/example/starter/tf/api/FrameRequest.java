package com.example.starter.tf.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册坐标系请求。
 *
 * @param frame 坐标系名称
 */
public record FrameRequest(@NotBlank String frame) {
}
