package com.example.starter.spectrum.api;

import com.example.starter.spectrum.domain.LeaseMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.SortedSet;

/**
 * 创建或修改租约请求。
 *
 * @param requestId       客户端请求幂等键（必传，非空）
 * @param expectedVersion 乐观版本：创建时为 {@code null} 或 0；修改时必须等于租约当前版本
 * @param startTime       UTC 半开时间区间起点（含），epoch 毫秒
 * @param endTime         UTC 半开时间区间终点（不含），epoch 毫秒
 * @param freqLowMhz      半开频率区间下界 MHz（含）
 * @param freqHighMhz     半开频率区间上界 MHz（不含）
 * @param mode            EXCLUSIVE 或 SHARED
 * @param eirpMw          EIRP，单位 mW（线性毫瓦），正数
 * @param cells           非空区域单元集合
 */
public record LeaseUpsertRequest(@NotNull String requestId,
                                 Integer expectedVersion,
                                @NotNull Long startTime,
                                @NotNull Long endTime,
                                @NotNull Double freqLowMhz,
                                @NotNull Double freqHighMhz,
                                @NotNull LeaseMode mode,
                                @Positive Double eirpMw,
                                @NotEmpty SortedSet<String> cells) {
}
