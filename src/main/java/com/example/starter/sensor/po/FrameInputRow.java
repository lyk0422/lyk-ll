package com.example.starter.sensor.po;

/**
 * 融合帧输入证据行。
 *
 * @param id                记录 ID
 * @param frameId           所属帧 ID
 * @param sensor            传感器类型
 * @param messageId         采用消息 ID；插值时为「前一ID~后一ID」
 * @param rawTs             原始时戳（插值时为插值时刻反算值），epoch 纳秒
 * @param correctedTs       校正后时戳，epoch 纳秒
 * @param timeDeltaNanos    与目标时刻之差，纳秒
 * @param interpolated      是否插值
 * @param anchorMessageIds  锚点消息 ID，逗号分隔
 * @param correctionVersion 实际使用的校正版本号
 * @param payload           采用/插值后的负载，可为 null
 */
public record FrameInputRow(
        Long id,
        String frameId,
        String sensor,
        String messageId,
        long rawTs,
        long correctedTs,
        long timeDeltaNanos,
        boolean interpolated,
        String anchorMessageIds,
        long correctionVersion,
        String payload) {
}
