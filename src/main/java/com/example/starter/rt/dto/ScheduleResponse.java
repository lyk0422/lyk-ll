package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 单条分次设备时长排程视图（发布结果、列表与占用查询共用）。
 * 窗口以 UTC 输出；时间戳 publishedAt/cancelledAt/executedAt 为 Asia/Shanghai 业务时区。
 *
 * @param id                  排程ID
 * @param patientId           患者ID
 * @param planCode            计划业务编码
 * @param planVersionId       冻结的计划版本ID
 * @param versionNo           冻结的计划版本号
 * @param fractionNo          分次号
 * @param deviceId            冻结的设备ID
 * @param complexityLevel     冻结的复杂度等级1-3
 * @param requiredMinutes     等级对应固定时长（10/20/30分钟）
 * @param windowStartUtc      窗口起点UTC（含）
 * @param windowEndUtc        窗口终点UTC（不含）
 * @param status              BOOKED/EXECUTED/CANCELLED
 * @param version             排程行版本：发布为1，取消或执行后为2
 * @param publishRequestId    发布幂等键
 * @param cancelRequestId     手动取消幂等键；改版系统取消或未取消为空
 * @param executedFractionId  受控执行后台账分次记录ID
 * @param publishedAt         发布时间
 * @param cancelledAt         取消时间
 * @param executedAt          受控执行完成时间
 */
public record ScheduleResponse(
        Long id,
        String patientId,
        String planCode,
        Long planVersionId,
        Integer versionNo,
        Integer fractionNo,
        String deviceId,
        Integer complexityLevel,
        Integer requiredMinutes,
        OffsetDateTime windowStartUtc,
        OffsetDateTime windowEndUtc,
        String status,
        Integer version,
        String publishRequestId,
        String cancelRequestId,
        Long executedFractionId,
        OffsetDateTime publishedAt,
        OffsetDateTime cancelledAt,
        OffsetDateTime executedAt) {
}
