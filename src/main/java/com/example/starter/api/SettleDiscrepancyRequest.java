package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 长短款销账请求：须在挂账发生日起 3 个自然日内（UTC 日界）提交，恰第 3 日受理，逾期升级冻结责任人。
 */
public record SettleDiscrepancyRequest(
        @NotBlank String requestId
) {
}
