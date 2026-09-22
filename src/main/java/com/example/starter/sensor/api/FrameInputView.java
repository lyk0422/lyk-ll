package com.example.starter.sensor.api;

import java.util.List;

/**
 * 融合帧输入证据视图。
 *
 * <p>每个必需输入都必须记录消息 ID、原始与校正时戳、与目标时刻的时间差及是否插值。
 *
 * @param sensor             传感器类型
 * @param messageId          实际采用或插值锚点的消息 ID；插值时格式为 {@code 前一ID~后一ID}
 * @param rawTimestamp       原始时戳（插值时为插值结果对应的原始时钟时刻）
 * @param correctedTimestamp 校正后时戳
 * @param timeDeltaNanos     校正时戳与目标融合时刻之差（纳秒），绝对值不超过窗口
 * @param interpolated       是否由相邻消息插值得到
 * @param anchorMessageIds   插值时前后锚点消息 ID（非插值时仅含一个元素）
 */
public record FrameInputView(
        String sensor,
        String messageId,
        Long rawTimestamp,
        Long correctedTimestamp,
        Long timeDeltaNanos,
        boolean interpolated,
        List<String> anchorMessageIds) {
}
