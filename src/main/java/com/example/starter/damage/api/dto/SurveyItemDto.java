package com.example.starter.damage.api.dto;

import com.example.starter.damage.api.Responsibility;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 单条残损登记项。 */
public record SurveyItemDto(
        @NotBlank String code,
        @NotBlank String location,
        @NotNull Responsibility responsibility,
        @Min(1) int quantity) {
}
