package com.example.starter.spectrum.domain;

import com.example.starter.spectrum.LeaseMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 租约聚合：主表状态 + 当前版本 + 区域集合 + 事件流。
 *
 * @param leaseId       租约标识
 * @param currentVersion 当前版本号
 * @param status        ACTIVE / CANCELLED
 * @param start         当前版本生效开始时刻（UTC，含）
 * @param end           当前版本生效结束时刻（UTC，不含）
 * @param freqLowMHz    当前版本频率下限 MHz（含）
 * @param freqHighMHz   当前版本频率上限 MHz（不含）
 * @param mode          当前版本模式
 * @param eirpMw        当前版本 EIRP（mW）
 * @param regions       当前版本区域单元集合
 * @param cancelledAtUtc 取消生效时刻；未取消为 null
 * @param events        事件流（按发生顺序）
 */
public record LeaseAggregate(
        String leaseId,
        int currentVersion,
        String status,
        Instant start,
        Instant end,
        BigDecimal freqLowMHz,
        BigDecimal freqHighMHz,
        LeaseMode mode,
        BigDecimal eirpMw,
        List<String> regions,
        Instant cancelledAtUtc,
        List<Event> events) {

    /**
     * 租约事件。
     *
     * @param version      事件发生时版本号
     * @param eventType    CREATED / MODIFIED / CANCELLED
     * @param detail       补充说明
     * @param createdAtUtc 事件发生时刻（UTC）
     */
    public record Event(int version, String eventType, String detail, Instant createdAtUtc) {
    }
}
