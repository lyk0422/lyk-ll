package com.example.starter.crane.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 释放授权请求。
 *
 * @param releaseType COMPLETED 作业完成 / CANCELLED 取消
 */
public record ReleaseRequest(@NotBlank String releaseType) {
}
