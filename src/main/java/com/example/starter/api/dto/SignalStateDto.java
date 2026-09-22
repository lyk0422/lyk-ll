package com.example.starter.api.dto;

/**
 * 查询信号最新状态的单项结果。
 *
 * @param signalName    信号名称
 * @param rawValue      最新原始值
 * @param physicalValue 最新物理值
 * @param unit          单位
 * @param enumText      枚举文本，无映射时为 null
 * @param frameSeq      产生该状态的帧序号
 * @param timestampNs   产生该状态的帧纳秒时戳
 */
public record SignalStateDto(
        String signalName,
        long rawValue,
        double physicalValue,
        String unit,
        String enumText,
        long frameSeq,
        long timestampNs) {
}
