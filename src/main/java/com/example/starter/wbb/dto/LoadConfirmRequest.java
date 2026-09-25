package com.example.starter.wbb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 装载确认请求：舱单进入 LOADED 状态，此后业载变更走 LMC 限额规则。
 */
public record LoadConfirmRequest(
        @NotBlank String requestId
) {
}
