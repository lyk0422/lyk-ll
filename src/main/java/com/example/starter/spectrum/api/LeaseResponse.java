package com.example.starter.spectrum.api;

import com.example.starter.spectrum.LeaseMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 租约视图（创建/修改/取消/查询的统一响应结构）。
 *
 * @param leaseId       租约标识
 * @param version       当前版本号
 * @param status        ACTIVE / CANCELLED
 * @param regions       区域单元集合
 * @param start         UTC 生效开始时刻（含）
 * @param end           UTC 生效结束时刻（不含）
 * @param freqLowMHz    频率下限 MHz（含）
 * @param freqHighMHz   频率上限 MHz（不含）
 * @param mode          EXCLUSIVE / SHARED
 * @param eirpMw        EIRP，单位 mW
 * @param cancelledAtUtc 取消生效时刻；未取消为 null
 * @param events        租约事件流
 */
public record LeaseResponse(
        String leaseId,
        int version,
        String status,
        List<String> regions,
        Instant start,
        Instant end,
        BigDecimal freqLowMHz,
        BigDecimal freqHighMHz,
        LeaseMode mode,
        BigDecimal eirpMw,
        Instant cancelledAtUtc,
        List<EventView> events) {

    /**
     * 租约事件视图。
     *
     * @param version      事件发生时版本号
     * @param eventType    CREATED / MODIFIED / CANCELLED
     * @param detail       补充说明
     * @param createdAtUtc 事件发生时刻（UTC）
     */
    public record EventView(int version, String eventType, String detail, Instant createdAtUtc) {
    }
}
