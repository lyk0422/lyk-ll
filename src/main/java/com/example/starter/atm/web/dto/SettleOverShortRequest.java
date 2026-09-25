package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 长短款销账请求：发生日起 3 个自然日内（UTC 日界）受理，恰第 3 日合法，逾期升级。 */
public record SettleOverShortRequest(@NotBlank String requestId) {
}
