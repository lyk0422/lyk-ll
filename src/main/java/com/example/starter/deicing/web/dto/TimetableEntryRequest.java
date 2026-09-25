package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 合成航班时刻表维护请求体。
 */
public record TimetableEntryRequest(
        @NotBlank String flightNumber,
        @Min(1) int hotMinutes) {
}
