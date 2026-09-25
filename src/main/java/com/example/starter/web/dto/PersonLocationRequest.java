package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 人员定位合成数据。onSite=true 表示 locatedAt 时刻仍在测区范围内、预警时必须撤离。
 */
public record PersonLocationRequest(
        @NotBlank String zoneCode,
        @NotBlank String personId,
        @NotNull Boolean onSite,
        @NotNull LocalDateTime locatedAt) {
}
