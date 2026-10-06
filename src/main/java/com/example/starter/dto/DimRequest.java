package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 人工调光请求。
 *
 * @param requestId   幂等键，由调用方生成，全局唯一
 * @param targetLevel 目标档位，0 为熄灭
 */
public record DimRequest(
        @NotBlank String requestId,
        @NotNull @Min(0) @Max(5) Integer targetLevel) {
}
