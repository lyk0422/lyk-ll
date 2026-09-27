package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建本地力矩采集会话请求：绑定工具日校验版本、工序卡与紧固点清单。
 *
 * <p>创建时工具日校验必须对服务当前 UTC 日有效；入账时另按各包测量时刻复核。
 *
 * @param expectedCount 计划采集包总数（序号 1..expectedCount），用于关闭时校验无缺号
 * @param pointCodes    绑定的紧固点清单，须全部存在于工序卡
 */
public record CollectionSessionRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String sessionNo,
        @NotBlank String cardNo,
        @NotBlank String toolCode,
        @NotNull @Positive Integer expectedCount,
        @NotEmpty List<@NotBlank String> pointCodes) {
}
