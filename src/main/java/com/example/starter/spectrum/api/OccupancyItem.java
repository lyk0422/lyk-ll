package com.example.starter.spectrum.api;

import com.example.starter.spectrum.domain.LeaseMode;

/**
 * 区域频段占用查询结果中的单条占用。
 *
 * @param leaseId     租约 ID
 * @param version     版本
 * @param mode        模式
 * @param eirpMw      EIRP mW
 * @param startTime   UTC 半开时间区间起点（含），epoch 毫秒
 * @param endTime     UTC 半开时间区间终点（不含），epoch 毫秒
 * @param freqLowMhz  半开频率区间下界 MHz（含）
 * @param freqHighMhz 半开频率区间上界 MHz（不含）
 */
public record OccupancyItem(long leaseId,
                            int version,
                            LeaseMode mode,
                            double eirpMw,
                            long startTime,
                            long endTime,
                            double freqLowMhz,
                            double freqHighMhz) {
}
