package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 设备占用查询项（仅占用中 SCHEDULED 排程项）。
 *
 * @param scheduleId     排程批次ID
 * @param itemId         排程项ID
 * @param patientId      患者ID
 * @param planCode       计划业务编码
 * @param fractionNo     分次号
 * @param level          复杂度等级
 * @param windowStartUtc 占用窗口开始（UTC，含）
 * @param windowEndUtc   占用窗口结束（UTC，不含）
 */
public record DeviceOccupancyItem(
        long scheduleId,
        long itemId,
        String patientId,
        String planCode,
        int fractionNo,
        int level,
        OffsetDateTime windowStartUtc,
        OffsetDateTime windowEndUtc) {
}
