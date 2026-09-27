package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 列尾装置提前解绑请求；仅发车前允许，同时撤销一致性确认。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 期望的装置当前版本
 */
public record TailUnbindRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion) {
}
