package com.example.starter.dto;

import java.time.Instant;

/**
 * 受控分段阶梯单步视图。
 *
 * @param minuteNo    分钟序号，从 1 开始
 * @param scheduledAt 该分钟计划时刻（UTC）= 启动时刻 + 序号分钟
 * @param powerKw     冻结的该分钟阶梯功率（kW）
 */
public record ControlledStepItem(int minuteNo, Instant scheduledAt, int powerKw) {
}
