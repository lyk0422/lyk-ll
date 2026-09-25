package com.example.starter.schedule.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 按日发布入参。
 *
 * @param stationCode 地面站编码
 * @param dayKey UTC 日期 yyyy-MM-dd
 */
public record PublishRequestDto(
        @NotBlank String stationCode,
        @NotBlank String dayKey) {
}
