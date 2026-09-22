package com.example.starter.sensor.po;

/**
 * 融合帧行。
 *
 * @param frameId          帧 ID
 * @param targetTs         目标融合时刻，epoch 纳秒
 * @param status           COMMITTED / REJECTED
 * @param committedAt      提交/判定墙钟时刻，epoch 毫秒
 * @param reason           拒绝原因码，COMMITTED 时为 null
 * @param reasonDetail     拒绝原因描述
 * @param correctionCutoff 构建时校正版本 created_at 截止线，毫秒；null 表示不限制
 */
public record FrameRow(
        String frameId,
        long targetTs,
        String status,
        long committedAt,
        String reason,
        String reasonDetail,
        Long correctionCutoff) {
}
