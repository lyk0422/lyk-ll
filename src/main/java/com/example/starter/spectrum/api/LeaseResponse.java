package com.example.starter.spectrum.api;

import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.domain.LeaseStatus;

import java.util.SortedSet;

/**
 * 租约响应（创建、修改、取消、查询统一形态）。
 *
 * @param leaseId        租约 ID
 * @param version        返回时租约版本
 * @param status         ACTIVE 或 CANCELLED
 * @param startTime      UTC 半开时间区间起点（含），epoch 毫秒
 * @param endTime        UTC 半开时间区间终点（不含），epoch 毫秒
 * @param freqLowMhz     半开频率区间下界 MHz（含）
 * @param freqHighMhz    半开频率区间上界 MHz（不含）
 * @param mode           EXCLUSIVE 或 SHARED
 * @param eirpMw         EIRP mW
 * @param cells          区域单元集合
 * @param cancelTime     取消生效时间 epoch 毫秒 UTC；未取消为 {@code null}
 * @param idempotentReplay 是否为幂等键的重复请求回放结果
 */
public record LeaseResponse(long leaseId,
                            int version,
                            LeaseStatus status,
                            long startTime,
                            long endTime,
                            double freqLowMhz,
                            double freqHighMhz,
                            LeaseMode mode,
                            double eirpMw,
                            SortedSet<String> cells,
                            Long cancelTime,
                            boolean idempotentReplay) {
}
