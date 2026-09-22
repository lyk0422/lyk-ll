package com.example.starter.sensor.api;

import jakarta.validation.constraints.NotNull;

/**
 * 融合帧构建请求。
 *
 * @param targetTimestamp 目标融合时刻（epoch 纳秒）
 * @param forceWait       true 时若水位线不足则返回 WAITING_FOR_WATERMARK 拒绝记录；
 *                        false 时直接基于已有数据判定缺帧/超窗
 */
public record FrameRequest(
        @NotNull Long targetTimestamp,
        Boolean forceWait) {
}
