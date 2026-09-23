package com.example.starter.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 检测开始请求：预留体积转为 CONSUMED。
 */
public record StartRequest(
        @NotNull(message = "不能为空") Integer expectedVersion) {
}
