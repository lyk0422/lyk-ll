package com.example.starter.water.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 河流生态流量预警触发请求；effectiveAt 缺省为当前 UTC 时刻。
 */
public record RestrictionRequest(
        @NotBlank String riverSection,
        String effectiveAt,
        String reason) {
}
