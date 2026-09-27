package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 设备占用查询项（窗口为 UTC 半开区间）。
 *
 * @param itemId           排程项ID
 * @param scheduleId       排程批次ID
 * @param patientId        患者ID
 * @param planCode         计划业务编码
 * @param planVersionId    冻结的计划版本ID
 * @param level            冻结的复杂度等级
 * @param fractionNo       分次号
 * @param windowStartUtc   占用窗口起点（UTC，含）
 * @param windowEndUtc     占用窗口终点（UTC，不含）
 * @param status           状态：SCHEDULED占用中/COMPLETED已完成/CANCELLED已取消
 * @param deliveredDoseCgy 实绩投递剂量(cGy)，未完成为空
 */
public record OccupancyItemView(
        Long itemId,
        Long scheduleId,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer level,
        Integer fractionNo,
        OffsetDateTime windowStartUtc,
        OffsetDateTime windowEndUtc,
        String status,
        Long deliveredDoseCgy) {
}
