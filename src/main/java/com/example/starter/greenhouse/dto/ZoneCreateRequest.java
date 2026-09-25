package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 分区创建请求（合成数据维护入口）。
 */
public record ZoneCreateRequest(
        @NotBlank(message = "不能为空") String zoneCode,
        @NotBlank(message = "不能为空") String name) {
}
