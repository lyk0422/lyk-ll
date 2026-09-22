package com.example.starter.sensor.api;

import java.util.List;

/**
 * 融合帧视图（已提交帧不可变）。
 *
 * @param frameId         帧 ID（与目标时刻一一对应，重复提交返回同一帧）
 * @param targetTimestamp 目标融合时刻（epoch 纳秒）
 * @param status          COMMITTED / REJECTED
 * @param committedAt     提交时刻（墙钟 epoch 毫秒）
 * @param inputs          各传感器输入证据（REJECTED 时为空列表）
 * @param reason          REJECTED 时的稳定原因码
 * @param reasonDetail    REJECTED 时可定位到传感器的原因描述
 * @param reconstructed   是否由事件重放重建得到
 */
public record FrameView(
        String frameId,
        Long targetTimestamp,
        String status,
        Long committedAt,
        List<FrameInputView> inputs,
        String reason,
        String reasonDetail,
        boolean reconstructed) {
}
