package com.example.starter.roaming.domain;

import java.time.LocalDateTime;

/**
 * 充电会话行。
 *
 * @param authAt 授权请求时刻（UTC）
 * @param startAt 会话启动时刻（UTC）
 * @param endAt 结束时刻（UTC），未结束为空
 * @param totalEnergyWh 累计电量 Wh，取最大抄表序号快照读数
 * @param lastMeterSeq 已归位最大抄表序号，0 表示无快照
 * @param batchId 所属清分批，NULL 未归批
 */
public record SessionRow(
        Long id,
        String sessionNo,
        String certFingerprint,
        String pointId,
        String homeOperator,
        String chargingOperator,
        LocalDateTime authAt,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String status,
        long totalEnergyWh,
        int lastMeterSeq,
        Long batchId,
        java.time.LocalDate settlementDay,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ENDED = "ENDED";
}
