package com.example.starter.spectrum.domain;

import java.util.SortedSet;

/**
 * 租约规格（单个版本的三维半开区间描述，与租约身份及版本号无关的值对象）。
 *
 * @param startTime   UTC 半开时间区间起点（含），epoch 毫秒
 * @param endTime     UTC 半开时间区间终点（不含），epoch 毫秒
 * @param freqLowMhz  半开频率区间下界 MHz（含）
 * @param freqHighMhz 半开频率区间上界 MHz（不含）
 * @param mode        独占或共享
 * @param eirpMw      EIRP，单位 mW（线性毫瓦），正数
 * @param cells       非空区域单元集合（合成数据）
 */
public record LeaseSpec(long startTime,
                        long endTime,
                        double freqLowMhz,
                        double freqHighMhz,
                        LeaseMode mode,
                        double eirpMw,
                        SortedSet<String> cells) {
}
