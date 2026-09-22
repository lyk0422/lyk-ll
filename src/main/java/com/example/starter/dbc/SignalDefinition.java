package com.example.starter.dbc;

import java.util.Map;

/**
 * 单个信号的 DBC 定义。
 *
 * @param name         信号名
 * @param startBit     DBC 起始位（Intel 为 LSB 位号；Motorola 为 sawtooth 起点位号）
 * @param length       信号位长（1-64）
 * @param endian       字节序
 * @param signed       是否有符号
 * @param factor       缩放系数（物理值 = 原始值 * factor + offset）
 * @param offset       偏移量
 * @param multiplexMode 多路复用角色
 * @param muxValue     当 multiplexMode=MUXED 时，要求的多路选择信号值；否则为 null
 * @param enumerations 原始值到枚举文本的映射，可为空
 */
public record SignalDefinition(
        String name,
        int startBit,
        int length,
        Endian endian,
        boolean signed,
        double factor,
        double offset,
        MultiplexMode multiplexMode,
        Long muxValue,
        Map<Long, String> enumerations
) {
}
