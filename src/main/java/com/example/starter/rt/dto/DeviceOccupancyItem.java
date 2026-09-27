package com.example.starter.rt.dto;

import java.time.Instant;

/**
 * 设备占用查询明细。
 *
 * @param itemId        排程明细ID
 * @param batchId       所属批次ID
 * @param patientId     患者ID
 * @param planCode      计划业务编码
 * @param planVersionId 冻结版本ID
 * @param fractionNo    分次号
 * @param level         冻结的复杂度等级
 * @param startAt       窗口开始（UTC，含起点）
 * @param endAt         窗口结束（UTC，不含终点）
 * @param status        明细状态：ACTIVE占用中/COMPLETED已执行释放/CANCELLED已取消释放
 */
public record DeviceOccupancyItem(
        Long itemId,
        Long batchId,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer fractionNo,
        Integer level,
        Instant startAt,
        Instant endAt,
        String status) {
}
