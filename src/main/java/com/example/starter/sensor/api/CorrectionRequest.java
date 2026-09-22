package com.example.starter.sensor.api;

import jakarta.validation.constraints.NotNull;

/**
 * 时钟漂移校正参数热更新请求。
 *
 * <p>校正公式：{@code corrected = round(raw * slope + offsetNanos)}。
 *
 * @param slope       漂移斜率（无量纲，1.0 表示无频偏）
 * @param offsetNanos 固定偏移（纳秒）
 * @param effectiveAt 生效时刻（事件时间 epoch 纳秒）；目标融合时刻大于等于该值的未提交候选才会使用
 */
public record CorrectionRequest(
        @NotNull Double slope,
        @NotNull Long offsetNanos,
        @NotNull Long effectiveAt) {
}
