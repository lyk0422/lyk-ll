package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 发电车占用台账条目：一次占用从开始到释放（或仍占用中）的完整记录。
 *
 * @param occupationId  占用记录ID
 * @param vehicleId     发电车ID
 * @param code          发电车编号
 * @param orderId       占用调度单ID
 * @param carriedOver   是否改派结转产生
 * @param occupiedAt    占用开始时刻（UTC）
 * @param releasedAt    占用释放时刻（UTC），空表示占用中
 * @param releaseReason 释放原因，空表示仍占用
 */
public record OccupationEntry(
        Long occupationId,
        Long vehicleId,
        String code,
        Long orderId,
        boolean carriedOver,
        LocalDateTime occupiedAt,
        LocalDateTime releasedAt,
        String releaseReason) {
}
