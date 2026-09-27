package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发电车调度整体改派结果。
 *
 * @param newOrderId            新调度单ID
 * @param oldOrderId            旧调度单ID
 * @param version               新单版本（从1开始）
 * @param status                新单状态
 * @param carriedOverVehicleIds 旧单中保留的已到场车ID
 * @param newVehicleIds         换入的新车ID
 * @param reassignCount         该工单累计改派次数
 * @param reassignedAt          UTC改派时刻
 * @param replayed              是否为同键同参重放
 */
public record GeneratorReassignResponse(
        Long newOrderId,
        Long oldOrderId,
        int version,
        String status,
        List<Long> carriedOverVehicleIds,
        List<Long> newVehicleIds,
        int reassignCount,
        LocalDateTime reassignedAt,
        boolean replayed) {
}
