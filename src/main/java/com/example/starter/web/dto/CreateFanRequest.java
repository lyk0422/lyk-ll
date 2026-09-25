package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建风机（合成数据维护入口）。
 */
public record CreateFanRequest(
        @NotBlank String code,
        @NotBlank String name
) {
}
