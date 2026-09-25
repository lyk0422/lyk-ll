package com.example.starter.water.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 为取水户创建计量点请求。
 */
public record CreateMeterRequest(@NotBlank String name) {
}
