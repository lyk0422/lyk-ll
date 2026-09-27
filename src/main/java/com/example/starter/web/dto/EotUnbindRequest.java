package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 受控作业提前解绑列尾装置请求；仅允许发车前操作，且同时撤销既有一致性确认。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 绑定对应装置的期望版本（成功变更加1）
 */
public record EotUnbindRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion) {
}
