package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建油机请求（合成主数据）。
 */
public record GeneratorRequest(
        @NotBlank String genCode,
        @NotBlank String genName
) {
}
