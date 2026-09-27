package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 列尾装置绑定请求；绑定冻结当前编组尾车与编组版本。
 *
 * @param requestId       幂等请求键
 * @param deviceNo        装置编号
 * @param expectedVersion 期望的装置当前版本
 */
public record TailBindRequest(
        @NotBlank String requestId,
        @NotBlank String deviceNo,
        @NotNull @Positive Integer expectedVersion) {
}
