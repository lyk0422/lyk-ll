package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 小时评估请求：评估指定方案某一自然小时（Asia/Shanghai）的各污染物均值。
 *
 * @param hourStart 目标自然小时起点（分钟秒必须为 00:00）
 */
public record EvaluateHourRequest(
        @NotNull LocalDateTime hourStart) {
}
