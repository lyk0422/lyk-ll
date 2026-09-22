package com.example.starter.web.dto;

/**
 * 单个信号解码值。
 *
 * @param name         信号名
 * @param rawValue     原始整数值（有符号时已按补码解释）
 * @param physicalValue 物理值 = rawValue * factor + offset
 * @param enumLabel    枚举文本；无枚举或未命中时为 null
 */
public record SignalValueDto(
        String name,
        long rawValue,
        double physicalValue,
        String enumLabel
) {
}
